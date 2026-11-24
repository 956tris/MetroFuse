package dev.metrofuse.sdk

import dev.metrofuse.api.AudioProvider
import dev.metrofuse.api.AudioSource
import dev.metrofuse.api.CatalogProvider
import dev.metrofuse.api.HookPhase
import dev.metrofuse.api.HttpRequest
import dev.metrofuse.api.HttpResponse
import dev.metrofuse.api.JsonService
import dev.metrofuse.api.Layer
import dev.metrofuse.api.LyricsProvider
import dev.metrofuse.api.MatchCandidate
import dev.metrofuse.api.MetadataDocument
import dev.metrofuse.api.MetadataProvider
import dev.metrofuse.api.MetadataSource
import dev.metrofuse.api.NativeComponent
import dev.metrofuse.api.NativeScreen
import dev.metrofuse.api.PlaybackState
import dev.metrofuse.api.Player
import dev.metrofuse.api.PluginContext
import dev.metrofuse.api.PluginNetwork
import dev.metrofuse.api.PluginResources
import dev.metrofuse.api.PluginSettings
import dev.metrofuse.api.PluginStorage
import dev.metrofuse.api.SearchProvider
import dev.metrofuse.api.ServiceContract
import dev.metrofuse.api.Subscription
import dev.metrofuse.api.Track
import dev.metrofuse.api.TrackIdentity
import dev.metrofuse.api.TrackMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginVideoTest {
    @Test
    fun requestProviderParsesConstraintsAndSerializesDirectAndSabrStreams() =
        runBlocking {
            val context = TestContext()
            try {
                var capturedRequest: PluginVideoRequest? = null
                context.registerVideoProvider(
                    PluginVideoRequestProvider { req ->
                        capturedRequest = req
                        PluginVideoStream(
                            url = "https://example.invalid/video.webm",
                            headers = mapOf("User-Agent" to "MetroFuse"),
                            mimeType = "video/webm",
                            codecs = "vp9",
                            width = 3840,
                            height = 2160,
                            bitrate = 18_000_000,
                            itag = 313,
                            contentLengthBytes = 987654321L,
                            expiresAtMillis = 4102444800000L,
                            sabrBootstrapJson = """{"videoId":"abcdefghijk"}""",
                            availableHeights = listOf(2160, 1440, 1080),
                            musicVideoId = "abcdefghijk",
                        )
                    },
                )
                val service = context.services.getValue(VIDEO_PLAYBACK_SERVICE)
                val track = Track("abcdefghijk", "Title", "Artist", "Album")
                val response =
                    service.call(
                        buildJsonObject {
                            put("track", Json.encodeToJsonElement(track))
                            put("maxHeight", 2160)
                            put("allowSabr", true)
                            put("allowHls", false)
                        },
                    )
                assertEquals("abcdefghijk", capturedRequest?.track?.id)
                assertEquals(2160, capturedRequest?.maxHeight)
                assertTrue(capturedRequest?.allowSabr == true)
                assertFalse(capturedRequest?.allowHls == true)
                assertEquals("https://example.invalid/video.webm", response["url"]?.jsonPrimitive?.content)
                assertEquals("""{"videoId":"abcdefghijk"}""", response["sabr"]?.jsonPrimitive?.content)
                assertEquals("vp9", response["codecs"]?.jsonPrimitive?.content)
                assertEquals(313, response["itag"]?.jsonPrimitive?.intOrNull)
                assertEquals(listOf(2160, 1440, 1080), response["availableHeights"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull })
                assertEquals("abcdefghijk", response["musicVideoId"]?.jsonPrimitive?.content)
            } finally {
                context.close()
            }
        }

    @Test
    fun legacyProviderWorksAndEmptyStreamsReturnEmptyObject() =
        runBlocking {
            val context = TestContext()
            try {
                context.registerVideoProvider(PluginVideoProvider { null })
                val service = context.services.getValue(VIDEO_PLAYBACK_SERVICE)
                val empty = service.call(buildJsonObject { put("track", Json.encodeToJsonElement(Track("a", "b", "c", "d"))) })
                assertEquals(JsonObject(emptyMap()), empty)

                context.registerVideoProvider(
                    PluginVideoProvider { PluginVideoStream(url = "https://example.invalid/1080.mp4", height = 1080) },
                )
                val direct =
                    context.services.getValue(VIDEO_PLAYBACK_SERVICE).call(
                        buildJsonObject { put("track", Json.encodeToJsonElement(Track("a", "b", "c", "d"))) },
                    )
                assertEquals("https://example.invalid/1080.mp4", direct["url"]?.jsonPrimitive?.content)
                assertNull(direct["sabr"])
            } finally {
                context.close()
            }
        }

    @Test
    fun supportsAnyProviderFormatIncludingBilibiliHeadersAndTwitchHlsByDefault() =
        runBlocking {
            val context = TestContext()
            try {
                var bilibiliRequest: PluginVideoRequest? = null
                context.registerVideoProvider(
                    PluginVideoRequestProvider { req ->
                        bilibiliRequest = req
                        PluginVideoStream(
                            url = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/stream.mp4",
                            headers = mapOf("Referer" to "https://www.bilibili.com/video/BV1xx411c7mD"),
                            mimeType = "video/mp4",
                            width = 1920,
                            height = 1080,
                            mediaId = "BV1xx411c7mD",
                        )
                    },
                )
                val bilibiliResponse =
                    context.services.getValue(VIDEO_PLAYBACK_SERVICE).call(
                        buildJsonObject {
                            put("track", Json.encodeToJsonElement(Track("BV1xx411c7mD", "Bili Video", "Uploader", "")))
                        },
                    )
                assertTrue(bilibiliRequest?.allowHls == true)
                assertEquals("BV1xx411c7mD", bilibiliResponse["mediaId"]?.jsonPrimitive?.content)
                assertEquals(
                    "https://www.bilibili.com/video/BV1xx411c7mD",
                    bilibiliResponse["headers"]
                        ?.let { it as? JsonObject }
                        ?.get("Referer")
                        ?.jsonPrimitive
                        ?.content,
                )

                context.registerVideoProvider(
                    PluginVideoProvider {
                        PluginVideoStream(
                            url = "https://video-weaver.jfk04.hls.ttvnw.net/v1/playlist/live.m3u8",
                            mimeType = "application/vnd.apple.mpegurl",
                            mediaId = "twitch:stream:999",
                        )
                    },
                )
                val twitchResponse =
                    context.services.getValue(VIDEO_PLAYBACK_SERVICE).call(
                        buildJsonObject {
                            put("track", Json.encodeToJsonElement(Track("twitch:stream:999", "Live Stream", "Streamer", "")))
                        },
                    )
                assertEquals(
                    "https://video-weaver.jfk04.hls.ttvnw.net/v1/playlist/live.m3u8",
                    twitchResponse["url"]?.jsonPrimitive?.content,
                )
                assertEquals("twitch:stream:999", twitchResponse["mediaId"]?.jsonPrimitive?.content)
            } finally {
                context.close()
            }
        }

    private class TestContext : PluginContext {
        override val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val services = linkedMapOf<String, JsonService>()
        override val settings =
            object : PluginSettings {
                override val values = MutableStateFlow(JsonObject(emptyMap()))

                override suspend fun set(
                    key: String,
                    value: JsonElement,
                ) {}
            }
        override val storage =
            object : PluginStorage {
                override suspend fun get(key: String): JsonElement? = null

                override suspend fun set(
                    key: String,
                    value: JsonElement?,
                ) {}
            }
        override val network =
            object : PluginNetwork {
                override suspend fun request(request: HttpRequest) = HttpResponse(200, emptyMap(), "")
            }
        override val resources =
            object : PluginResources {
                override val platformResources: Any? = null

                override suspend fun readAsset(path: String) = ByteArray(0)
            }
        override val matcher =
            object : TrackMatcher {
                override fun selectSafeCandidates(
                    metadata: Track,
                    candidates: List<MatchCandidate>,
                    providerOrder: List<String>,
                    maximumCandidates: Int,
                ) = emptyList<MatchCandidate>()
            }
        override val player =
            object : Player {
                override val state = MutableStateFlow(PlaybackState(Track("id", "t", "a", "al")))

                override fun toggle() = Unit

                override fun seek(position: Long) = Unit

                override fun next() = Unit
            }
        override val metadata = MutableStateFlow(Track("id", "t", "a", "al"))
        override val metadataDocument = MutableStateFlow(MetadataDocument(TrackIdentity("id")))

        override fun registerUi(
            point: String,
            screen: NativeScreen,
            layer: Layer,
        ) {}

        override fun registerComponent(
            type: String,
            component: NativeComponent,
        ) {}

        override fun registerService(
            name: String,
            service: JsonService,
        ) {
            services[name] = service
        }

        override fun registerContract(contract: ServiceContract) {}

        override fun registerHook(
            name: String,
            phase: HookPhase,
            observer: JsonService,
        ) {}

        override suspend fun callService(
            name: String,
            request: JsonObject,
        ) = JsonObject(emptyMap())

        override fun subscribe(
            event: String,
            handler: suspend (JsonObject) -> Unit,
        ) = Subscription {}

        override suspend fun publish(
            event: String,
            payload: JsonObject,
        ) {}

        override fun registerMetadata(provider: MetadataProvider) {}

        override fun registerMetadataSource(provider: MetadataSource) {}

        override fun registerAudio(
            id: String,
            provider: AudioProvider,
        ) {}

        override fun registerAudioSource(provider: AudioSource) {}

        override fun registerLyricsProvider(provider: LyricsProvider) {}

        override fun registerSearchProvider(provider: SearchProvider) {}

        override fun registerCatalogProvider(provider: CatalogProvider) {}

        override fun log(message: String) {}

        fun close() = scope.cancel()
    }
}
