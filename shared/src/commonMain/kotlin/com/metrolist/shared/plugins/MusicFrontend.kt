package com.metrolist.shared.plugins

import androidx.compose.runtime.snapshotFlow
import com.metrolist.shared.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

data class MusicFrontend(
    val owner: String,
    val title: String,
    val icon: String? = null,
)

sealed interface HomeSelection {
    data object Offline : HomeSelection

    data class Plugin(
        val pluginId: String,
    ) : HomeSelection

    val persisted: String
        get() =
            when (this) {
                Offline -> "offline"
                is Plugin -> "plugin:$pluginId"
            }

    fun available(frontends: List<MusicFrontend>): HomeSelection =
        if (this is Plugin && frontends.none { it.owner == pluginId }) Offline else this
}

/** Accept the former raw-owner preference once, then persist the canonical representation. */
fun String.toHomeSelection(): HomeSelection =
    when {
        isEmpty() || this == "offline" -> HomeSelection.Offline
        startsWith("plugin:") -> substringAfter("plugin:").takeIf(String::isNotBlank)?.let(HomeSelection::Plugin) ?: HomeSelection.Offline
        else -> HomeSelection.Plugin(this)
    }

fun effectiveHomeSelection(): HomeSelection =
    Settings.shared.musicFrontendOwner
        .toHomeSelection()
        .available(PluginServicesStore.frontends.value)

fun effectiveMusicFrontendOwner(): String = (effectiveHomeSelection() as? HomeSelection.Plugin)?.pluginId.orEmpty()

fun musicFrontendOwnerChanges(): Flow<String> =
    combine(snapshotFlow { Settings.shared.musicFrontendOwner }, PluginServicesStore.frontends) { saved, frontends ->
        (saved.toHomeSelection().available(frontends) as? HomeSelection.Plugin)?.pluginId.orEmpty()
    }.distinctUntilChanged()

fun selectHomeSource(selection: HomeSelection) {
    require(selection.available(PluginServicesStore.frontends.value) == selection)
    Settings.shared.musicFrontendOwner = selection.persisted
}

fun selectMusicFrontend(owner: String) = selectHomeSource(if (owner.isEmpty()) HomeSelection.Offline else HomeSelection.Plugin(owner))
