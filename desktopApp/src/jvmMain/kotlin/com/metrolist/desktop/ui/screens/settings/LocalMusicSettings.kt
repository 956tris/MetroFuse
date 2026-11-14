package com.metrolist.desktop.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.storage.DesktopLocalMusicLibrary
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.local_music_add_folder
import com.metrolist.shared.generated.resources.local_music_folders_empty
import com.metrolist.shared.generated.resources.local_music_remove_folder
import com.metrolist.shared.generated.resources.local_music_scan
import com.metrolist.shared.generated.resources.local_music_scan_error
import com.metrolist.shared.generated.resources.local_music_scan_result
import com.metrolist.shared.generated.resources.local_music_scanning
import com.metrolist.shared.generated.resources.local_music_summary
import com.metrolist.shared.generated.resources.local_music_title
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.settings.SettingsPageId
import com.metrolist.shared.ui.settings.StorageSection
import com.metrolist.shared.ui.settings.StorageSettingRow
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LocalMusicSettingsSection() {
    val state by DesktopLocalMusicLibrary.state.collectAsState()
    val scope = rememberCoroutineScope()
    var choosingFolder by remember { mutableStateOf(false) }
    var folderError by remember { mutableStateOf(false) }
    val busy = state.scanning || choosingFolder
    StorageSection(title = stringResource(Res.string.local_music_title)) {
        StorageSettingRow(
            title = stringResource(Res.string.local_music_add_folder),
            subtitle = stringResource(Res.string.local_music_summary),
            isFirst = true,
            enabled = !busy,
            onClick = {
                choosingFolder = true
                folderError = false
                scope.launch {
                    try {
                        FileKit.openDirectoryPicker()?.file?.let { folder ->
                            withContext(Dispatchers.IO) { DesktopLocalMusicLibrary.addFolder(folder) }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        folderError = true
                    } finally {
                        choosingFolder = false
                    }
                }
            },
            trailingContent = { Icon(MetrolistIcons.Filled.Add, null) },
        )
        state.folders.forEach { folder ->
            StorageSettingRow(
                title = folder.name,
                subtitle = folder.path,
                trailingContent = {
                    IconButton(enabled = !busy, onClick = { DesktopLocalMusicLibrary.removeFolder(folder) }) {
                        Icon(MetrolistIcons.Outlined.Delete, stringResource(Res.string.local_music_remove_folder))
                    }
                },
            )
        }
        StorageSettingRow(
            title = stringResource(if (state.scanning) Res.string.local_music_scanning else Res.string.local_music_scan),
            subtitle =
                when {
                    state.failed || folderError -> stringResource(Res.string.local_music_scan_error)
                    state.foundTracks != null -> stringResource(Res.string.local_music_scan_result, state.foundTracks!!)
                    state.folders.isEmpty() -> stringResource(Res.string.local_music_folders_empty)
                    else -> null
                },
            isLast = true,
            enabled = !busy && state.folders.isNotEmpty(),
            onClick = DesktopLocalMusicLibrary::scan,
            trailingContent = { Icon(MetrolistIcons.Outlined.Refresh, null) },
        )
        if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
internal fun LocalMusicLibraryActions() {
    val state by DesktopLocalMusicLibrary.state.collectAsState()
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(MetrolistIcons.Outlined.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(stringResource(Res.string.local_music_title), style = MaterialTheme.typography.titleSmall)
                if (state.scanning) {
                    Text(
                        stringResource(Res.string.local_music_scanning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (state.failed) {
                    Text(
                        stringResource(Res.string.local_music_scan_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            TextButton(onClick = {
                UiState.selectedSettingsPage = SettingsPageId.STORAGE
                UiState.showSettings = true
            }) { Text(stringResource(Res.string.local_music_add_folder)) }
            IconButton(enabled = !state.scanning && state.folders.isNotEmpty(), onClick = DesktopLocalMusicLibrary::scan) {
                Icon(MetrolistIcons.Outlined.Refresh, stringResource(Res.string.local_music_scan))
            }
        }
    }
}
