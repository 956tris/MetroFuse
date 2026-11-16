package dev.metrofuse.sdk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.metrofuse.api.Player
import dev.metrofuse.api.PluginContext
import dev.metrofuse.api.SearchResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** UI plugins supply localized labels; the SDK owns capability checks and playback units. */
data class PlaybackLabels(
    val previous: String,
    val play: String,
    val pause: String,
    val next: String,
)

@Composable
fun PluginPlaybackControls(
    player: Player,
    labels: PlaybackLabels,
    modifier: Modifier = Modifier,
) {
    val state by player.state.collectAsState()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = player::previous, enabled = "previous" in player.capabilities) { Text(labels.previous) }
        Button(onClick = player::toggle, enabled = "toggle" in player.capabilities && state.track.id.isNotBlank()) {
            Text(if (state.playing) labels.pause else labels.play)
        }
        Button(onClick = player::next, enabled = "next" in player.capabilities && state.queue.isNotEmpty()) { Text(labels.next) }
    }
}

/** Track durations, player positions and seek commands are milliseconds. */
fun playbackFraction(
    positionMs: Long,
    durationMs: Long,
): Float = if (durationMs <= 0) 0f else (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f)

@Composable
fun PluginSeekBar(
    player: Player,
    modifier: Modifier = Modifier,
) {
    val state by player.state.collectAsState()
    var dragging by remember(state.track.id) { mutableStateOf<Float?>(null) }
    Slider(
        value = dragging ?: playbackFraction(state.position, state.track.duration),
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { player.seek((it * state.track.duration).toLong()) }
            dragging = null
        },
        modifier = modifier.fillMaxWidth(),
        enabled = "seek" in player.capabilities && state.track.duration > 0,
    )
}

/** Typed access to host search, including the selected provider and offline library. */
suspend fun PluginContext.searchUiCatalog(
    query: String,
    limit: Int = 40,
): SearchResult {
    val response =
        callService(
            "ui.search/v1",
            buildJsonObject {
                put("query", query)
                put("limit", limit.coerceIn(1, 100))
            },
        )
    return Json.decodeFromJsonElement(SearchResult.serializer(), response.getValue("results"))
}
