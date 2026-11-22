/**
 * Metrolist Project (C) 2026, GPL-3.0. See Git history for contributors.
 * Adapted from MetroFuse ProviderMatchDialog at 5ba186773b5135d259da8d72f48c7b9492d7a08e.
 */
package com.metrolist.shared.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.audio_match_automatic
import com.metrolist.shared.generated.resources.audio_match_automatic_description
import com.metrolist.shared.generated.resources.audio_match_empty
import com.metrolist.shared.generated.resources.audio_match_failed
import com.metrolist.shared.generated.resources.audio_match_unplayable
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.manual_audio_match
import com.metrolist.shared.generated.resources.retry
import com.metrolist.shared.generated.resources.search
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.isLive
import com.metrolist.shared.plugins.AudioMatchCandidate
import com.metrolist.shared.plugins.AudioMatchOverrides
import com.metrolist.shared.plugins.HomeSelection
import com.metrolist.shared.plugins.PluginServices
import com.metrolist.shared.plugins.PluginServicesStore
import com.metrolist.shared.plugins.toHomeSelection
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.utils.formatDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun rememberAudioMatchAction(): PlayerControlAction? {
    val frontends by PluginServicesStore.frontends.collectAsState()
    val services = PluginServicesStore.current ?: return null
    val controller = services.audioMatchingController ?: return null
    val selection by remember(controller) {
        controller.state
            .map { state ->
                (state.queue.getOrNull(state.currentQueueIndex) ?: state.currentSong) to
                    (state.castState.isConnected || state.isVideoModeActive)
            }.distinctUntilChanged()
    }.collectAsState((controller.state.value.currentSong) to false)
    val song = selection.first ?: return null
    if (!controller.supportsManualAudioMatching || !services.canMatchAudio || selection.second || song.isLocal ||
        song.id.startsWith("local_") || song.isPodcast || song.isLive ||
        Settings.shared.musicFrontendOwner
            .toHomeSelection()
            .available(frontends) !is HomeSelection.Plugin
    ) {
        return null
    }
    var showDialog by remember(song.id) { mutableStateOf(false) }
    val saved = Settings.shared.audioMatchOverridesJson
    val current = remember(saved, song.id) { AudioMatchOverrides.decode(saved)[song.id] }
    if (showDialog) {
        AudioMatchDialog(song, current, services, controller) { showDialog = false }
    }
    return PlayerControlAction(
        icon = MetrolistIcons.Outlined.Link,
        contentDescription = stringResource(Res.string.manual_audio_match),
        onClick = { showDialog = true },
        checked = current != null,
    )
}

@Composable
private fun AudioMatchDialog(
    song: SongItem,
    current: AudioMatchCandidate?,
    services: PluginServices,
    controller: PlaybackController,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val defaultQuery =
        remember(song.id) {
            listOfNotNull(song.title, song.artists.firstOrNull()?.name).filter(String::isNotBlank).joinToString(" ")
        }
    var query by remember(song.id) { mutableStateOf(defaultQuery) }
    var retry by remember { mutableStateOf(0) }
    var candidates by remember(song.id) { mutableStateOf<List<AudioMatchCandidate>?>(null) }
    var searchFailed by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf(false) }
    var applyFailed by remember { mutableStateOf(false) }
    LaunchedEffect(song.id, query, retry) {
        candidates = null
        searchFailed = false
        delay(250)
        if (query.isBlank()) {
            candidates = emptyList()
            return@LaunchedEffect
        }
        try {
            candidates = services.audioMatches(song, query.takeUnless { it == defaultQuery })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            searchFailed = true
            candidates = emptyList()
        }
    }

    fun apply(candidate: AudioMatchCandidate?) {
        scope.launch {
            applying = true
            applyFailed = false
            try {
                if (controller.supportsManualAudioMatching && services.setAudioMatch(song.id, candidate)) {
                    controller.rematchCurrentAudio(song.id)
                    onDismiss()
                } else {
                    applyFailed = true
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                applyFailed = true
            } finally {
                applying = false
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.manual_audio_match)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(1024) },
                    label = { Text(stringResource(Res.string.search)) },
                    singleLine = true,
                    enabled = !applying,
                    modifier = Modifier.fillMaxWidth(),
                )
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.audio_match_automatic)) },
                    supportingContent = { Text(stringResource(Res.string.audio_match_automatic_description)) },
                    leadingContent = { RadioButton(selected = current == null, onClick = null) },
                    modifier = Modifier.clickable(enabled = !applying) { apply(null) },
                )
                HorizontalDivider()
                if (applyFailed) Text(stringResource(Res.string.audio_match_unplayable))
                when {
                    candidates == null || applying -> {
                        Box(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }

                    searchFailed -> {
                        Text(stringResource(Res.string.audio_match_failed))
                        TextButton(onClick = { retry++ }) { Text(stringResource(Res.string.retry)) }
                    }

                    candidates.isNullOrEmpty() -> {
                        Text(stringResource(Res.string.audio_match_empty))
                    }

                    else -> {
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            items(candidates.orEmpty(), key = { "${it.owner}:${it.provider}:${it.trackId}" }) { candidate ->
                                val selected =
                                    current?.let {
                                        it.provider == candidate.provider && it.owner == candidate.owner && it.trackId == candidate.trackId
                                    } == true
                                ListItem(
                                    headlineContent = { Text(candidate.title, maxLines = 2) },
                                    overlineContent = { Text(candidate.providerName, maxLines = 1) },
                                    supportingContent = {
                                        Text(
                                            listOf(candidate.artist, candidate.album).filter(String::isNotBlank).joinToString(" · "),
                                            maxLines = 2,
                                        )
                                    },
                                    leadingContent = { RadioButton(selected = selected, onClick = null) },
                                    trailingContent =
                                        candidate.durationMs.takeIf { it > 0 }?.let { duration ->
                                            { Text(formatDuration(duration)) }
                                        },
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = !applying) { apply(candidate) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}
