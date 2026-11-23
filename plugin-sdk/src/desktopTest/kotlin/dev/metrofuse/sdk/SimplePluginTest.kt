package dev.metrofuse.sdk

import androidx.compose.runtime.Composable
import dev.metrofuse.api.AudioCapabilities
import dev.metrofuse.api.AudioProvider
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.AudioRequest
import dev.metrofuse.api.AudioResolution
import dev.metrofuse.api.AudioResult
import dev.metrofuse.api.AudioSource
import dev.metrofuse.api.CatalogProvider
import dev.metrofuse.api.HostSettings
import dev.metrofuse.api.JsonService
import dev.metrofuse.api.Layer
import dev.metrofuse.api.LyricsProvider
import dev.metrofuse.api.LyricsRequest
import dev.metrofuse.api.LyricsResult
import dev.metrofuse.api.MetadataContribution
import dev.metrofuse.api.MetadataDocument
import dev.metrofuse.api.MetadataProvider
import dev.metrofuse.api.MetadataRequest
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
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.SearchResult
import dev.metrofuse.api.ServiceContract
import dev.metrofuse.api.StreamDescriptor
import dev.metrofuse.api.StreamTransport
import dev.metrofuse.api.Subscription
import dev.metrofuse.api.Track
import dev.metrofuse.api.TrackIdentity
import dev.metrofuse.api.TrackMatcher
import dev.metrofuse.api.HookPhase
import dev.metrofuse.api.HttpRequest
import dev.metrofuse.api.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SimplePluginTest {
    private class SampleDslPlugin : MetroFusePlugin() {
        var stopped = false

        override suspend fun PluginBuilder.setup() {
            audio("simple.audio") { req -> AudioResolution("https://example.invalid/${req.trackId}.mp3", "audio/mpeg") }
            audioSource("simple.source") { query ->
                AudioResult(StreamDescriptor(StreamTransport.DIRECT, "https://example.invalid/${query.identity.canonicalId}.flac"))
            }
            search("simple.search") { req -> SearchResult(listOf(Track("t1", req.query, "Artist", "Album"))) }
            service("simple.echo/v1") { body -> body }
            context.log("configured")
        }

        override suspend fun onStop() {
            stopped = true
        }
    }

    @Test
    fun metroFusePluginDslRegistersProvidersServicesAndTypedSettings() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val audioProviders = mutableMapOf<String, AudioProvider>()
            val audioSources = mutableListOf<AudioSource>()
            val searchProviders = mutableListOf<SearchProvider>()
            val services = mutableMapOf<String, JsonService>()
            val logs = mutableListOf<String>()
            val settingsState =
                MutableStateFlow<Map<String, JsonElement>>(
                    mapOf(
                        "title" to JsonPrimitive("Metro"),
                        "enabled" to JsonPrimitive(true),
                        "ratio" to JsonPrimitive(1.5),
                        "limit" to JsonPrimitive(25),
                    ),
                )
            val context =
                object : PluginContext {
                    override val scope = scope
                    override val settings =
                        object : PluginSettings {
                            override val values: StateFlow<Map<String, JsonElement>> = settingsState

                            override suspend fun set(
                                key: String,
                                value: JsonElement,
                            ) {
                                settingsState.value = settingsState.value + (key to value)
                            }
                        }
                    override val storage =
                        object : PluginStorage {
                            override suspend fun get(key: String): JsonElement? = null

                            override suspend fun set(
                                key: String,
                                value: JsonElement?,
                            ) = Unit
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
                                candidates: List<dev.metrofuse.api.MatchCandidate>,
                                providerOrder: List<String>,
                                maximumCandidates: Int,
                            ) = emptyList<dev.metrofuse.api.MatchCandidate>()
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
                    ) = Unit

                    override fun registerComponent(
                        type: String,
                        component: NativeComponent,
                    ) = Unit

                    override fun registerService(
                        name: String,
                        service: JsonService,
                    ) {
                        services[name] = service
                    }

                    override fun registerContract(contract: ServiceContract) = Unit

                    override fun registerHook(
                        name: String,
                        phase: HookPhase,
                        observer: JsonService,
                    ) = Unit

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
                    ) = Unit

                    override fun registerMetadata(provider: MetadataProvider) = Unit

                    override fun registerMetadataSource(provider: MetadataSource) = Unit

                    override fun registerAudio(
                        id: String,
                        provider: AudioProvider,
                    ) {
                        audioProviders[id] = provider
                    }

                    override fun registerAudioSource(provider: AudioSource) {
                        audioSources += provider
                    }

                    override fun registerLyricsProvider(provider: LyricsProvider) = Unit

                    override fun registerSearchProvider(provider: SearchProvider) {
                        searchProviders += provider
                    }

                    override fun registerCatalogProvider(provider: CatalogProvider) = Unit

                    override fun log(message: String) {
                        logs += message
                    }
                }
            val plugin = SampleDslPlugin()
            plugin.start(context)
            assertEquals(setOf("simple.audio"), audioProviders.keys)
            assertEquals("simple.source", audioSources.single().id)
            assertEquals("simple.search", searchProviders.single().id)
            assertEquals(JsonPrimitive("ok"), services.getValue("simple.echo/v1").call(buildJsonObject { put("status", "ok") })["status"])
            assertEquals("Metro", context.settings.string("title"))
            assertEquals(true, context.settings.boolean("enabled"))
            assertEquals(1.5, context.settings.double("ratio"))
            assertEquals(25, context.settings.int("limit"))
            assertEquals(listOf("configured"), logs)
            plugin.stop()
            assertTrue(plugin.stopped)
            scope.cancel()
        }
}
