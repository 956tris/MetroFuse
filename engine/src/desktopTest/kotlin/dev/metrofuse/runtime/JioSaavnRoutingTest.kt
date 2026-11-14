package dev.metrofuse.runtime

import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.TrackIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JioSaavnRoutingTest {
    @Test
    fun productionQuickJsRoutesAllCatalogCallsAndSeparatesCacheWhenSettingChanges() =
        runBlocking {
            val source = File(requireNotNull(System.getProperty("metrofuse.root")), "plugins/providers/jiosaavn")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("38346591".toByteArray(), "DES"))
            val encrypted = Base64.getEncoder().encodeToString(cipher.doFinal("https://aac.saavncdn.com/123/fixture_96.mp4".toByteArray()))
            val hook =
                """
                const originalInit=globalThis.plugin.init;
                const requests=[];
                const fixture={id:'western_1',type:'song',title:'No Idea',more_info:{album:'HEAVEN OR HELL',album_id:'album_1',duration:'153',
                  artistMap:{primary_artists:[{name:'Don Toliver'}]},encrypted_media_url:'$encrypted','320kbps':'true'}};
                globalThis.plugin.init=async context=>{
                  await originalInit({...context,http:async request=>{
                    requests.push(request);
                    const call=decodeURIComponent(request.url.match(/[?&]__call=([^&]+)/)[1]);
                    let body={results:[]};
                    if(call==='search.getResults') body={results:[fixture]};
                    if(call==='song.getDetails') body={songs:request.headers['X-Real-IP']?[fixture]:[]};
                    if(call==='content.getAlbumDetails') body={id:'album_1',title:'HEAVEN OR HELL',songs:[fixture]};
                    if(call==='artist.getArtistPageDetails') body={artistId:'artist_1',name:'Don Toliver',topSongs:[fixture]};
                    if(call==='playlist.getDetails') body={listid:'playlist_1',listname:'Playlist',list:[fixture]};
                    return {status:200,headers:{},body:JSON.stringify(body)};
                  }});
                  context.service('fixture.requests/v1',()=>({requests}));
                };
                """.trimIndent()
            val bytes =
                ByteArrayOutputStream().use { output ->
                    ZipOutputStream(output).use { zip ->
                        source.walkTopDown().filter(File::isFile).forEach { file ->
                            val name = file.relativeTo(source).invariantSeparatorsPath
                            zip.putNextEntry(ZipEntry(name))
                            zip.write((file.readText() + if (name == "index.js") "\n$hook" else "").toByteArray())
                            zip.closeEntry()
                        }
                    }
                    output.toByteArray()
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runtime = Runtime(Files.createTempDirectory("metrofuse-jiosaavn-routing-").toFile(), "desktop", scope, EngineTestHost())
            try {
                runtime.initialize()
                runtime.install(ByteArrayInputStream(bytes), "jiosaavn.mfp")
                runtime.enable("metrofuse.jiosaavn")
                assertEquals(
                    "Enabled",
                    runtime.plugins.value
                        .single()
                        .state,
                    runtime.logs.value.joinToString(),
                )
                val search = runtime.pipelines.search(SearchRequest("Don Toliver & No Idea", 5))
                val track = search.tracks.single()
                assertEquals("Don Toliver", track.artist)
                val identity = TrackIdentity("jiosaavn:track:western_1")
                assertNotNull(runtime.pipelines.audio(AudioQuery(identity, "desktop"), includeBuiltin = false).stream)
                assertNotNull(runtime.pipelines.album("metrofuse:metrofuse.jiosaavn:album_1"))
                assertNotNull(runtime.pipelines.artist("metrofuse:metrofuse.jiosaavn:artist_1"))
                assertNotNull(runtime.pipelines.playlist("metrofuse:metrofuse.jiosaavn:playlist_1"))

                fun JsonObject.headers() = getValue("headers").jsonObject

                suspend fun requests() =
                    (
                        runtime.registry
                            .invoke(
                                "fixture.requests/v1",
                                buildJsonObject {
                                },
                            ).getValue("requests") as JsonArray
                    ).map { it.jsonObject }
                val routed = requests()
                val ip =
                    routed
                        .first()
                        .headers()
                        .getValue("X-Real-IP")
                        .jsonPrimitive.content
                assertTrue(Regex("103\\.57\\.226\\.\\d+").matches(ip))
                assertTrue(
                    routed.all { it.headers()["X-Real-IP"] == JsonPrimitive(ip) && it.headers()["X-Forwarded-For"] == JsonPrimitive(ip) },
                )
                assertTrue(
                    routed.filter { "search." in it.getValue("url").jsonPrimitive.content }.all {
                        "q=Don%20Toliver%20%26%20No%20Idea" in
                            it.getValue("url").jsonPrimitive.content
                    },
                )
                runtime.settingsFor("metrofuse.jiosaavn").set("regionalCatalog", JsonPrimitive(false))
                // Call the provider directly so the host's stream cache cannot mask plugin cache behavior.
                val audio =
                    runtime.registry
                        .audioSources()
                        .single { it.id == "jiosaavn.audio" }
                        .provider
                        .resolve(AudioQuery(identity, "desktop"))
                assertTrue(audio.stream == null)
                val unrouted = requests().last()
                assertTrue("X-Real-IP" !in unrouted.headers() && "X-Forwarded-For" !in unrouted.headers())
            } finally {
                runtime.close()
                scope.cancel()
            }
        }
}
