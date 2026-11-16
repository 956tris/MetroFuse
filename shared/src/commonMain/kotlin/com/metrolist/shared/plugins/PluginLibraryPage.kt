package com.metrolist.shared.plugins

import com.metrolist.shared.model.YTItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

data class PluginLibraryPage(
    val items: List<YTItem> = emptyList(),
    val continuation: String? = null,
    val complete: Boolean = true,
)

data class PluginLibraryAccount(
    val revision: Long = 0L,
    val loggedIn: Boolean = false,
    val busy: Boolean = false,
)

fun pluginLibraryAccountKey(owner: String): String {
    val account = PluginServicesStore.libraryAccounts.value[owner] ?: PluginLibraryAccount()
    return "plugin:$owner:${account.revision}:${account.loggedIn}"
}

fun musicLibraryAccountChanges(): Flow<Pair<String, PluginLibraryAccount?>> =
    combine(musicFrontendOwnerChanges(), PluginServicesStore.libraryAccounts) { owner, accounts -> owner to accounts[owner] }
        .distinctUntilChanged()
