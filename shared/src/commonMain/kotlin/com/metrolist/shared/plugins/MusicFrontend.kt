package com.metrolist.shared.plugins

import androidx.compose.runtime.snapshotFlow
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

data class MusicFrontend(
    val owner: String,
    val title: String,
)

fun effectiveMusicFrontendOwner(): String =
    Settings.shared.musicFrontendOwner
        .takeIf { selected -> PluginServicesStore.frontends.value.any { it.owner == selected } }
        .orEmpty()

fun musicFrontendOwnerChanges(): Flow<String> =
    combine(snapshotFlow { Settings.shared.musicFrontendOwner }, PluginServicesStore.frontends) { selected, frontends ->
        selected.takeIf { owner -> frontends.any { it.owner == owner } }.orEmpty()
    }.distinctUntilChanged()

fun selectMusicFrontend(owner: String) {
    require(owner.isEmpty() || PluginServicesStore.frontends.value.any { it.owner == owner })
    Settings.shared.musicFrontendOwner = owner
}
