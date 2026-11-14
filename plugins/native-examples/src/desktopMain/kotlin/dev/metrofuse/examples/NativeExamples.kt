package dev.metrofuse.examples

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.metrofuse.api.AudioCapabilities
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.AudioResult
import dev.metrofuse.api.AudioSource
import dev.metrofuse.api.NativePlugin
import dev.metrofuse.api.NativeScreen
import dev.metrofuse.api.PluginContext
import dev.metrofuse.api.ProviderError
import dev.metrofuse.api.SearchProvider
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.SearchResult
import dev.metrofuse.api.StreamDescriptor
import dev.metrofuse.api.StreamTransport
import dev.metrofuse.api.Track
import dev.metrofuse.api.UiContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

class MinimalPlugin : NativePlugin {
    override suspend fun start(context: PluginContext) {
        context.log("Native plugin activated through the host API")
    }
}

/** Resolves a developer-controlled local WAV URI carried in TrackIdentity.identifiers. */
class FixtureAudioPlugin : NativePlugin {
    override suspend fun start(context: PluginContext) {
        context.registerSearchProvider(object : SearchProvider {
            override val id = "example.native.audio.search"
            override suspend fun search(request: SearchRequest): SearchResult {
                val configuredUri = context.settings.values.value.getValue("fixtureUri").jsonPrimitive.content
                if (configuredUri.isBlank() || request.limit <= 0) return SearchResult(emptyList())
                val uri = URI(configuredUri)
                if (uri.scheme != "file") return SearchResult(emptyList())
                val title = uri.path.substringAfterLast('/')
                if (!title.contains(request.query, ignoreCase = true)) return SearchResult(emptyList())
                return SearchResult(listOf(Track("example.native.audio.track", title, "", "", extras = mapOf("fixtureUri" to configuredUri))))
            }
        })
        context.registerAudioSource(object : AudioSource {
            override val id = "example.native.audio.local"
            override val capabilities = AudioCapabilities(setOf(StreamTransport.DIRECT), codecs = setOf("pcm"), formats = setOf("wav"))
            override suspend fun resolve(request: AudioQuery): AudioResult {
                val uri = request.identity.identifiers["fixtureUri"]
                    ?: return AudioResult(error = ProviderError("UNSUPPORTED", "Track has no local fixture"))
                if (!uri.startsWith("file:/")) return AudioResult(error = ProviderError("UNSUPPORTED", "Fixture must be a local file URI"))
                return AudioResult(StreamDescriptor(StreamTransport.DIRECT, uri, "audio/wav", codec = "pcm"))
            }
        })
    }
}

class ComposeSettingsPlugin : NativePlugin {
    override suspend fun start(context: PluginContext) {
        val pluginContext = context
        context.registerUi("plugin.example.native.settings", object : NativeScreen {
            @Composable
            override fun Content(context: UiContext) {
                val values by pluginContext.settings.values.collectAsState()
                Column {
                    Box(Modifier.fillMaxWidth().height(160.dp)) {
                        GalaxyStarOverlay(skyColors = listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surface), animated = values.getValue("enabled").jsonPrimitive.boolean)
                    }
                    Switch(
                        checked = values.getValue("enabled").jsonPrimitive.boolean,
                        onCheckedChange = { checked -> pluginContext.scope.launch { pluginContext.settings.set("enabled", JsonPrimitive(checked)) } },
                    )
                    Button(onClick = context.player::toggle) { Text(values.getValue("playbackLabel").jsonPrimitive.content) }
                }
            }
        })
    }
}
