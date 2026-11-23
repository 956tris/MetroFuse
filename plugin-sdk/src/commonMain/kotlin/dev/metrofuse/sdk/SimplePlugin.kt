package dev.metrofuse.sdk

import androidx.compose.runtime.Composable
import dev.metrofuse.api.AudioCapabilities
import dev.metrofuse.api.AudioProvider
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.AudioRequest
import dev.metrofuse.api.AudioResolution
import dev.metrofuse.api.AudioResult
import dev.metrofuse.api.AudioSource
import dev.metrofuse.api.CatalogAlbum
import dev.metrofuse.api.CatalogArtist
import dev.metrofuse.api.CatalogPlaylist
import dev.metrofuse.api.CatalogProvider
import dev.metrofuse.api.JsonService
import dev.metrofuse.api.Layer
import dev.metrofuse.api.LyricsProvider
import dev.metrofuse.api.LyricsRequest
import dev.metrofuse.api.LyricsResult
import dev.metrofuse.api.MetadataContribution
import dev.metrofuse.api.MetadataRequest
import dev.metrofuse.api.MetadataSource
import dev.metrofuse.api.NativeComponent
import dev.metrofuse.api.NativePlugin
import dev.metrofuse.api.NativeScreen
import dev.metrofuse.api.PluginContext
import dev.metrofuse.api.PluginSettings
import dev.metrofuse.api.SearchProvider
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.SearchResult
import dev.metrofuse.api.StreamTransport
import dev.metrofuse.api.Subscription
import dev.metrofuse.api.UiContext
import dev.metrofuse.api.UiNode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@DslMarker
annotation class MetroFusePluginDsl

/**
 * Simplified base class for Plugin API 1.2.0 native plugins.
 * Subclasses implement [setup] with [PluginBuilder] DSL helpers while remaining 100% compatible with [NativePlugin].
 */
abstract class MetroFusePlugin : NativePlugin {
    override suspend fun start(context: PluginContext) {
        PluginBuilder(context).setup()
    }

    protected open suspend fun PluginBuilder.setup() {}

    override suspend fun stop() {
        onStop()
    }

    protected open suspend fun onStop() {}
}

inline fun PluginContext.configure(block: PluginBuilder.() -> Unit) {
    PluginBuilder(this).block()
}

@MetroFusePluginDsl
class PluginBuilder(
    val context: PluginContext,
) {
    fun audio(
        id: String,
        capabilities: Set<String> = emptySet(),
        platforms: Set<String> = emptySet(),
        resolve: suspend (AudioRequest) -> AudioResolution,
    ) {
        context.registerAudio(
            id,
            object : AudioProvider {
                override val capabilities = capabilities
                override val platformRequirements = platforms

                override suspend fun resolve(request: AudioRequest): AudioResolution = resolve(request)
            },
        )
    }

    fun audioSource(
        id: String,
        transports: Set<StreamTransport> = setOf(StreamTransport.DIRECT, StreamTransport.RANGE_HTTP),
        codecs: Set<String> = emptySet(),
        formats: Set<String> = emptySet(),
        requirements: Set<String> = emptySet(),
        resolve: suspend (AudioQuery) -> AudioResult,
    ) {
        val caps =
            AudioCapabilities(
                transports = transports,
                codecs = codecs,
                formats = formats,
                requirements = requirements,
            )
        context.registerAudioSource(
            object : AudioSource {
                override val id = id
                override val capabilities = caps

                override suspend fun resolve(request: AudioQuery): AudioResult = resolve(request)
            },
        )
    }

    fun metadataSource(
        id: String,
        resolve: suspend (MetadataRequest) -> MetadataContribution?,
    ) {
        context.registerMetadataSource(
            object : MetadataSource {
                override val id = id

                override suspend fun resolve(request: MetadataRequest): MetadataContribution? = resolve(request)
            },
        )
    }

    fun search(
        id: String,
        search: suspend (SearchRequest) -> SearchResult,
    ) {
        context.registerSearchProvider(
            object : SearchProvider {
                override val id = id

                override suspend fun search(request: SearchRequest): SearchResult = search(request)
            },
        )
    }

    fun lyrics(
        id: String,
        resolve: suspend (LyricsRequest) -> LyricsResult?,
    ) {
        context.registerLyricsProvider(
            object : LyricsProvider {
                override val id = id

                override suspend fun lyrics(request: LyricsRequest): LyricsResult? = resolve(request)
            },
        )
    }

    fun catalog(
        id: String,
        album: (suspend (String) -> CatalogAlbum?)? = null,
        artist: (suspend (String) -> CatalogArtist?)? = null,
        playlist: (suspend (String) -> CatalogPlaylist?)? = null,
    ) {
        context.registerCatalogProvider(
            object : CatalogProvider {
                override val id = id

                override suspend fun album(id: String): CatalogAlbum? = album?.invoke(id)

                override suspend fun artist(id: String): CatalogArtist? = artist?.invoke(id)

                override suspend fun playlist(id: String): CatalogPlaylist? = playlist?.invoke(id)
            },
        )
    }

    fun ui(
        point: String,
        layer: Layer = Layer.REPLACE,
        content: @Composable (UiContext) -> Unit,
    ) {
        context.registerUi(
            point,
            object : NativeScreen {
                @Composable
                override fun Content(context: UiContext) = content(context)
            },
            layer,
        )
    }

    fun component(
        type: String,
        content: @Composable (UiNode, UiContext) -> Unit,
    ) {
        context.registerComponent(
            type,
            object : NativeComponent {
                @Composable
                override fun Content(
                    node: UiNode,
                    context: UiContext,
                ) = content(node, context)
            },
        )
    }

    fun service(
        name: String,
        call: suspend (JsonObject) -> JsonObject,
    ) {
        context.registerService(
            name,
            object : JsonService {
                override suspend fun call(request: JsonObject): JsonObject = call(request)
            },
        )
    }

    fun onEvent(
        event: String,
        handler: suspend (JsonObject) -> Unit,
    ): Subscription = context.subscribe(event, handler)
}

fun PluginSettings.string(
    key: String,
    default: String = "",
): String = (values.value[key] as? JsonPrimitive)?.contentOrNull ?: default

fun PluginSettings.boolean(
    key: String,
    default: Boolean = false,
): Boolean = runCatching { values.value[key]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: default

fun PluginSettings.double(
    key: String,
    default: Double = 0.0,
): Double = runCatching { values.value[key]?.jsonPrimitive?.doubleOrNull }.getOrNull() ?: default

fun PluginSettings.int(
    key: String,
    default: Int = 0,
): Int = runCatching { values.value[key]?.jsonPrimitive?.intOrNull }.getOrNull() ?: default
