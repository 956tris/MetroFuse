package dev.metrofuse.api

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

const val API_VERSION = "1.1.0"
const val COMPOSE_ABI = "kotlin-2.4.21_compose-1.13.0-beta02_api-1.1.0"

@Serializable data class Track(val id: String, val title: String, val artist: String, val album: String, val duration: Long = 0, val artwork: String = "", val extras: Map<String, String> = emptyMap())
@Serializable data class PlaybackState(val track: Track, val playing: Boolean = false, val position: Long = 0, val queue: List<Track> = emptyList())
interface Player {
    val state: StateFlow<PlaybackState>
    val capabilities: Set<String> get() = setOf("toggle", "play", "pause", "next", "seek", "stop")
    fun toggle()
    fun seek(position: Long)
    fun next()
    fun play() { if (!state.value.playing) toggle() }
    fun pause() { if (state.value.playing) toggle() }
    fun previous() { throw UnsupportedOperationException("Previous is unavailable") }
    fun stop() { pause(); seek(0) }
    fun setVolume(volume: Float) { throw UnsupportedOperationException("Volume is unavailable") }
    fun playTracks(tracks: List<Track>, startIndex: Int = 0) { throw UnsupportedOperationException("Queue replacement is unavailable") }
    fun addToQueue(tracks: List<Track>) { throw UnsupportedOperationException("Queue insertion is unavailable") }
}

@Serializable data class UiNode(val id: String, val type: String, val props: Map<String, String> = emptyMap(), val children: List<UiNode> = emptyList())
enum class Layer { REPLACE, BEFORE, AFTER }
data class UiContribution(val owner: String, val point: String, val layer: Layer = Layer.REPLACE, val tree: UiNode? = null, val native: NativeScreen? = null)
interface NativeScreen { @Composable fun Content(context: UiContext) }
interface NativeComponent { @Composable fun Content(node: UiNode, context: UiContext) }
interface UiExtensions { @Composable fun Render(point: String, fallback: @Composable () -> Unit) }
@Serializable
data class UiTarget(val id: String, val title: String, val kind: String)

data class UiContext(
    val player: Player,
    val metadata: StateFlow<Track>,
    val navigate: (String) -> Unit,
    val extensions: UiExtensions? = null,
) {
    var target: UiTarget? = null
        private set

    constructor(
        player: Player,
        metadata: StateFlow<Track>,
        navigate: (String) -> Unit,
        extensions: UiExtensions? = null,
        target: UiTarget?,
    ) : this(player, metadata, navigate, extensions) {
        this.target = target
    }
}

interface MetadataProvider { suspend fun resolve(track: Track): Track }
@Serializable data class AudioRequest(val trackId: String, val platform: String)
@Serializable data class AudioResolution(val uri: String? = null, val mimeType: String? = null, val error: String? = null)
interface AudioProvider {
    val capabilities: Set<String>
    val platformRequirements: Set<String>
    suspend fun resolve(request: AudioRequest): AudioResolution
}
/** JSON contract is the cross-language service boundary. Names MUST include a major version. */
interface JsonService { suspend fun call(request: JsonObject): JsonObject }
interface PluginContext {
    val scope: CoroutineScope
    val settings: PluginSettings
    val storage: PluginStorage
    val network: PluginNetwork
    val resources: PluginResources
    val matcher: TrackMatcher
    val player: Player
    val metadata: StateFlow<Track>
    val metadataDocument: StateFlow<MetadataDocument>
    fun registerUi(point: String, screen: NativeScreen, layer: Layer = Layer.REPLACE)
    fun registerComponent(type: String, component: NativeComponent)
    fun registerService(name: String, service: JsonService)
    fun registerContract(contract: ServiceContract)
    fun registerHook(name: String, phase: HookPhase, observer: JsonService)
    suspend fun callService(name: String, request: JsonObject): JsonObject
    fun subscribe(event: String, handler: suspend (JsonObject) -> Unit): Subscription
    suspend fun publish(event: String, payload: JsonObject)
    fun registerMetadata(provider: MetadataProvider)
    fun registerMetadataSource(provider: MetadataSource)
    fun registerAudio(id: String, provider: AudioProvider)
    fun registerAudioSource(provider: AudioSource)
    fun registerLyricsProvider(provider: LyricsProvider)
    fun registerSearchProvider(provider: SearchProvider)
    fun registerCatalogProvider(provider: CatalogProvider)
    fun log(message: String)
}
interface NativePlugin {
    suspend fun start(context: PluginContext)
    suspend fun stop() {}
}

@Serializable data class Manifest(
    val id: String, val name: String, val version: String, val author: String,
    val api: String = API_VERSION, val runtime: String,
    val platforms: List<String> = listOf("desktop", "android"),
    val script: String? = null, val entryClass: String? = null,
    val composeAbi: String? = null,
    val dependencies: Map<String, String> = emptyMap(),
    val capabilities: List<String> = emptyList(),
    val integrity: Map<String, String> = emptyMap(),
    val description: String = "",
    val icon: String? = null,
    val minimumHostVersion: String? = null,
    val settings: List<SettingDefinition> = emptyList(),
    val home: HomeSourceMetadata? = null,
)
