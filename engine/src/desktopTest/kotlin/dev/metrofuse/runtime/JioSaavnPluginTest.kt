package dev.metrofuse.runtime

import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.HomeLayout
import dev.metrofuse.api.HomeShelf
import dev.metrofuse.api.Manifest
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.TrackIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JioSaavnPluginTest {
    @Test
    fun packedPluginSearchCatalogFullAudioAndPersistenceLive() =
        runBlocking {
            assumeTrue("Set METROFUSE_JIOSAAVN_LIVE=1 to run live service validation", System.getenv("METROFUSE_JIOSAAVN_LIVE") == "1")
            val root = File(requireNotNull(System.getProperty("metrofuse.root")))
            val archive = root.resolve("build/plugin-examples/jiosaavn.mfp")
            assertTrue(archive.isFile, "Pack the production JioSaavn MFP before the live test")
            val data = Files.createTempDirectory("metrofuse-jiosaavn-live-").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            var runtime = Runtime(data, "desktop", scope, EngineTestHost())
            try {
                runtime.initialize()
                assertTrue(runtime.plugins.value.isEmpty())
                archive.inputStream().use { runtime.install(it, archive.name) }
                assertTrue(runtime.registry.searchProviders().isEmpty())
                runtime.enable("metrofuse.jiosaavn")
                assertEquals(
                    "Enabled",
                    runtime.plugins.value
                        .single()
                        .state,
                    runtime.logs.value.joinToString(),
                )
                val results = runtime.pipelines.search(SearchRequest("Tum Hi Ho", 5))
                val track = results.tracks.firstOrNull { it.title == "Tum Hi Ho" && it.artist.contains("Arijit Singh") }
                assertNotNull(track, "Live search did not return the known original track")
                assertTrue(track.id.startsWith("metrofuse:metrofuse.jiosaavn:"))
                val identity =
                    TrackIdentity(track.extras.getValue("providerTrackId"), mapOf("jiosaavn" to track.extras.getValue("jiosaavn")))
                // Exercise the real cold audio pipeline and its configured default deadline.
                val audio = runtime.pipelines.audio(AudioQuery(identity, "desktop"), includeBuiltin = false)
                val stream =
                    assertNotNull(
                        audio.stream,
                        "Cold production audio pipeline failed: ${audio.error?.code}; ${runtime.pipelines.diagnostics.value}",
                    )
                assertEquals("full song", stream.attributes["content"])
                assertEquals("jiosaavn.audio", stream.attributes["providerId"])
                assertTrue((stream.durationSeconds ?: 0) > 240)
                assertEquals("audio/mp4", stream.mimeType)
                root.resolve("build/jiosaavn-live-stream-url.txt").writeText(stream.locator)
                val byteCount =
                    withContext(Dispatchers.IO) {
                        val connection = URI(stream.locator).toURL().openConnection() as HttpURLConnection
                        try {
                            connection.connectTimeout = 10000
                            connection.readTimeout = 10000
                            connection.setRequestProperty("Range", "bytes=0-2047")
                            assertTrue(connection.responseCode in listOf(200, 206), "Live CDN returned HTTP ${connection.responseCode}")
                            connection.inputStream.use { input ->
                                val bytes = input.readNBytes(2048)
                                assertEquals("ftyp", String(bytes, 4, 4, Charsets.US_ASCII))
                                bytes.size
                            }
                        } finally {
                            connection.disconnect()
                        }
                    }
                assertEquals(2048, byteCount)
                val metadata = runtime.pipelines.metadata(MetadataMapping.fromTrack(track.copy(id = identity.canonicalId)))
                assertEquals(identity.identifiers["jiosaavn"], metadata.identity.identifiers["jiosaavn"])
                val album = runtime.pipelines.album("metrofuse:metrofuse.jiosaavn:${track.extras.getValue("albumId")}")
                assertNotNull(album, "Live album lookup failed")
                assertTrue(album.tracks.any { it.extras["jiosaavn"] == identity.identifiers["jiosaavn"] })
                runtime.settingsFor("metrofuse.jiosaavn").set("previewEnabled", JsonPrimitive(false))
                runtime.disable("metrofuse.jiosaavn")
                assertTrue(runtime.registry.searchProviders().isEmpty())
                assertTrue(runtime.pipelines.audio(AudioQuery(identity, "desktop"), includeBuiltin = false).stream == null)
                runtime.enable("metrofuse.jiosaavn")
                runtime.hotReplace("metrofuse.jiosaavn")
                assertEquals(JsonPrimitive(false), runtime.settingsFor("metrofuse.jiosaavn").values.value["previewEnabled"])
                runtime.close()
                runtime = Runtime(data, "desktop", scope, EngineTestHost())
                runtime.initialize()
                assertEquals(
                    "Enabled",
                    runtime.plugins.value
                        .single()
                        .state,
                )
                assertEquals(JsonPrimitive(false), runtime.settingsFor("metrofuse.jiosaavn").values.value["previewEnabled"])
                assertNotNull(runtime.pipelines.audio(AudioQuery(identity, "desktop"), includeBuiltin = false).stream)
                root.resolve("build/jiosaavn-live-verification.json").writeText(
                    buildJsonObject {
                        put("title", track.title)
                        put("artist", track.artist)
                        put("album", album.title)
                        put("durationSeconds", stream.durationSeconds)
                        put("bitrate", stream.bitrate)
                        put("mimeType", stream.mimeType)
                        put("cdnBytesRead", byteCount)
                        put("packedPlugin", archive.name)
                        put("reloadAndRestart", true)
                    }.toString(),
                )
            } finally {
                runtime.close()
                scope.cancel()
            }
        }

    @Test
    fun productionPluginRegistersAndParsesCatalogFixturesWithoutNetwork() =
        runBlocking {
            val root = File(requireNotNull(System.getProperty("metrofuse.root")))
            val source = File(root, "plugins/providers/jiosaavn")
            val manifest = codec.decodeFromString<Manifest>(source.resolve("manifest.json").readText())
            val hook =
                """
                const originalInit=globalThis.plugin.init;
                globalThis.plugin.init=async context=>{
                  await originalInit(context);
                  context.service('fixture.jiosaavn/v1',request=>({
                    track:parseSong(request.song), preview:previewStream(request.song,request.enabled??true),
                    album:parseAlbum(request.album,'album-id'),artist:parseArtist(request.artist,'artist-id'),
                    playlist:parsePlaylist(request.playlist,'playlist-id'),id:catalogId(request.identity),
                    home:homeShelves(request.home??{}),
                    decrypted:decryptMediaUrl(request.encrypted)??null,
                    full:fullSongStream(request.song,request.quality),
                    resolved:resolveSongStream(request.song,request.quality,request.enabled??true)
                  }));
                };
                """.trimIndent()
            val entries =
                mapOf(
                    "manifest.json" to codec.encodeToString(manifest),
                    "index.js" to source.resolve("index.js").readText() + "\n" + hook,
                    "modules/catalog.js" to source.resolve("modules/catalog.js").readText(),
                    "modules/media.js" to source.resolve("modules/media.js").readText(),
                    "modules/request.js" to source.resolve("modules/request.js").readText(),
                    "modules/home.js" to source.resolve("modules/home.js").readText(),
                )
            val bytes =
                ByteArrayOutputStream().use { output ->
                    ZipOutputStream(output).use { zip ->
                        entries.forEach { (name, value) ->
                            zip.putNextEntry(ZipEntry(name))
                            zip.write(value.toByteArray())
                            zip.closeEntry()
                        }
                    }
                    output.toByteArray()
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runtime = Runtime(Files.createTempDirectory("metrofuse-jiosaavn-").toFile(), "desktop", scope, EngineTestHost())
            try {
                runtime.initialize()
                runtime.install(ByteArrayInputStream(bytes), "jiosaavn.mfp")
                runtime.enable(manifest.id)
                assertEquals(
                    "Enabled",
                    runtime.plugins.value
                        .single()
                        .state,
                    runtime.logs.value.joinToString(),
                )
                assertEquals(
                    "jiosaavn.search",
                    runtime.registry
                        .searchProviders()
                        .single()
                        .id,
                )
                assertEquals(
                    "jiosaavn.catalog",
                    runtime.registry
                        .catalogProviders()
                        .single()
                        .id,
                )
                val song =
                    codec.parseToJsonElement(
                        """{
                          "id":"song_1","type":"song","title":"Fixture &amp; song",
                          "image":"https://fixture.invalid/150x150.jpg",
                          "more_info":{"album":"Album","duration":"123",
                            "artistMap":{"primary_artists":[{"name":"Artist &#39; One"}]},
                            "vlink":"https://jiotunepreview.jio.com/test-fixture.mp3"}
                        }""",
                    )
                val request =
                    buildJsonObject {
                        put("song", song)
                        put("identity", codec.parseToJsonElement("""{"canonicalId":"jiosaavn:track:song_1"}"""))
                        put("album", codec.parseToJsonElement("""{"title":"Fixture album","songs":[]} """))
                        put("artist", codec.parseToJsonElement("""{"name":"Fixture artist","topSongs":{"songs":[]}}"""))
                        put("playlist", codec.parseToJsonElement("""{"listname":"Fixture playlist","list":[]}"""))
                    }
                val result = runtime.registry.invoke("fixture.jiosaavn/v1", request)
                val track = result.getValue("track").jsonObject
                assertEquals("jiosaavn:track:song_1", track.getValue("id").jsonPrimitive.content)
                assertEquals("Fixture & song", track.getValue("title").jsonPrimitive.content)
                assertEquals("Artist ' One", track.getValue("artist").jsonPrimitive.content)
                assertEquals("123000", track.getValue("duration").jsonPrimitive.content)
                assertEquals("https://fixture.invalid/500x500.jpg", track.getValue("artwork").jsonPrimitive.content)
                assertEquals("song_1", result.getValue("id").jsonPrimitive.content)
                val stream =
                    result
                        .getValue("preview")
                        .jsonObject
                        .getValue("stream")
                        .jsonObject
                assertEquals("DIRECT", stream.getValue("transport").jsonPrimitive.content)
                assertTrue("bitrate" !in stream && "codec" !in stream && "durationSeconds" !in stream)
                assertEquals(
                    "Fixture album",
                    result
                        .getValue("album")
                        .jsonObject
                        .getValue("title")
                        .jsonPrimitive.content,
                )
                assertNotNull(result.getValue("artist"))
                assertNotNull(result.getValue("playlist"))
                val homeFixture =
                    codec.parseToJsonElement(
                        """
                    {"modules":{"songs":{"source":"songs","title":"Tracks &amp; more","position":2},
                      "mixed":{"source":"mixed","title":"Albums","position":1}},
                      "songs":[$song],
                      "mixed":[{"id":"album_1","type":"album","title":"Album"},{"id":"radio_1","type":"radio","title":"Unsupported"}]}
                """,
                    )
                val homes = runtime.registry.invoke("fixture.jiosaavn/v1", JsonObject(request + ("home" to homeFixture)))
                val shelves = codec.decodeFromJsonElement<List<HomeShelf>>(homes.getValue("home"))
                assertEquals(listOf("Albums", "Tracks & more"), shelves.map { it.title })
                assertEquals(listOf(HomeLayout.CARDS, HomeLayout.ROWS_GRID), shelves.map { it.layout })
                assertEquals(listOf(1, 1), shelves.map { it.items.size })
                val deniedSong =
                    JsonObject(
                        song.jsonObject +
                            (
                                "more_info" to
                                    JsonObject(
                                        song.jsonObject.getValue("more_info").jsonObject +
                                            ("vlink" to JsonPrimitive("https://aac.saavncdn.com/not-a-public-preview.mp4")),
                                    )
                            ),
                    )
                val denied = runtime.registry.invoke("fixture.jiosaavn/v1", JsonObject(request + ("song" to deniedSong)))
                assertEquals(
                    "PREVIEW_UNAVAILABLE",
                    denied
                        .getValue("preview")
                        .jsonObject
                        .getValue("error")
                        .jsonObject
                        .getValue("code")
                        .jsonPrimitive.content,
                )
                val malformed =
                    runtime.registry.invoke(
                        "fixture.jiosaavn/v1",
                        buildJsonObject {
                            put("song", codec.parseToJsonElement("""{"id":"../escape","title":"Fixture"}"""))
                        },
                    )
                assertEquals(JsonNull, malformed.getValue("track"))
                val audio =
                    runtime.registry
                        .audioSources()
                        .first {
                            it.id == "jiosaavn.audio"
                        }.provider
                        .resolve(AudioQuery(TrackIdentity("other:track"), "desktop"))
                assertEquals("IDENTITY_REQUIRED", audio.error!!.code)

                fun encrypt(value: String): String {
                    val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
                    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("38346591".toByteArray(), "DES"))
                    return Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray()))
                }
                val locator = "https://aac.saavncdn.com/123/fixture_96.mp4"
                val encrypted = encrypt(locator)
                val fullSong =
                    JsonObject(
                        song.jsonObject + (
                            "more_info" to
                                JsonObject(
                                    song.jsonObject.getValue("more_info").jsonObject +
                                        mapOf(
                                            "encrypted_media_url" to JsonPrimitive(encrypted),
                                            "320kbps" to JsonPrimitive("true"),
                                        ),
                                )
                        ),
                    )

                suspend fun resolveFixture(
                    value: JsonObject = fullSong,
                    quality: JsonObject = buildJsonObject {},
                    enabled: Boolean = true,
                ): JsonObject =
                    runtime.registry.invoke(
                        "fixture.jiosaavn/v1",
                        buildJsonObject {
                            put("song", value)
                            put("quality", quality)
                            put("enabled", enabled)
                            put("encrypted", encrypted)
                        },
                    )
                val full = resolveFixture()
                assertEquals(locator, full.getValue("decrypted").jsonPrimitive.content)
                val fullStream =
                    full
                        .getValue("resolved")
                        .jsonObject
                        .getValue("stream")
                        .jsonObject
                assertEquals(locator.replace("_96.mp4", "_320.mp4"), fullStream.getValue("locator").jsonPrimitive.content)
                assertEquals("320000", fullStream.getValue("bitrate").jsonPrimitive.content)
                assertEquals("123", fullStream.getValue("durationSeconds").jsonPrimitive.content)
                assertEquals("audio/mp4", fullStream.getValue("mimeType").jsonPrimitive.content)
                assertTrue("sampleRate" !in fullStream && "expiresAtMillis" !in fullStream)
                val low = resolveFixture(quality = buildJsonObject { put("preset", "LOW") })
                assertEquals(
                    locator,
                    low
                        .getValue("resolved")
                        .jsonObject
                        .getValue("stream")
                        .jsonObject
                        .getValue("locator")
                        .jsonPrimitive.content,
                )
                val limited = resolveFixture(quality = buildJsonObject { put("maxBitrate", 48000) })
                assertEquals(
                    "48000",
                    limited
                        .getValue("resolved")
                        .jsonObject
                        .getValue("stream")
                        .jsonObject
                        .getValue("bitrate")
                        .jsonPrimitive.content,
                )
                val unavailable = resolveFixture(quality = buildJsonObject { put("maxBitrate", 1000) })
                assertEquals(
                    "QUALITY_UNAVAILABLE",
                    unavailable
                        .getValue("resolved")
                        .jsonObject
                        .getValue("error")
                        .jsonObject
                        .getValue("code")
                        .jsonPrimitive.content,
                )
                val lossless = resolveFixture(quality = buildJsonObject { put("lossless", true) })
                assertEquals(
                    "LOSSLESS_UNAVAILABLE",
                    lossless
                        .getValue("resolved")
                        .jsonObject
                        .getValue("error")
                        .jsonObject
                        .getValue("code")
                        .jsonPrimitive.content,
                )
                val no320 =
                    JsonObject(
                        fullSong +
                            ("more_info" to JsonObject(fullSong.getValue("more_info").jsonObject + ("320kbps" to JsonPrimitive(false)))),
                    )
                assertEquals(
                    "160000",
                    resolveFixture(
                        no320,
                    ).getValue("resolved").jsonObject.getValue("stream").jsonObject.getValue("bitrate").jsonPrimitive.content,
                )
                for (invalid in listOf(
                    "not-base64",
                    encrypt("https://saavncdn.com.evil.invalid/test_96.mp4"),
                    encrypt("https://saavncdn.com@evil.invalid/test_96.mp4"),
                )) {
                    val decoded = runtime.registry.invoke("fixture.jiosaavn/v1", buildJsonObject { put("encrypted", invalid) })
                    assertEquals(JsonNull, decoded.getValue("decrypted"))
                }
                val fallback = resolveFixture(song.jsonObject)
                assertEquals(
                    "public JioTune preview excerpt",
                    fallback
                        .getValue(
                            "resolved",
                        ).jsonObject
                        .getValue("stream")
                        .jsonObject
                        .getValue("attributes")
                        .jsonObject
                        .getValue("content")
                        .jsonPrimitive.content,
                )
                assertEquals(
                    "STREAM_UNAVAILABLE",
                    resolveFixture(
                        song.jsonObject,
                        enabled = false,
                    ).getValue("resolved").jsonObject.getValue("error").jsonObject.getValue("code").jsonPrimitive.content,
                )
                runtime.disable(manifest.id)
                assertTrue(runtime.registry.searchProviders().isEmpty())
                assertEquals(0, runtime.resources().sessions)
            } finally {
                runtime.close()
                scope.cancel()
            }
        }
}
