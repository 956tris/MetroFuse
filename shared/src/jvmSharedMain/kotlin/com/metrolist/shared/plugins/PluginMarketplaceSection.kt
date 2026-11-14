package com.metrolist.shared.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.all
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.details
import com.metrolist.shared.generated.resources.plugins_author_version
import com.metrolist.shared.generated.resources.plugins_fetch
import com.metrolist.shared.generated.resources.plugins_install
import com.metrolist.shared.generated.resources.plugins_installed
import com.metrolist.shared.generated.resources.plugins_marketplace
import com.metrolist.shared.generated.resources.plugins_marketplace_android
import com.metrolist.shared.generated.resources.plugins_marketplace_category
import com.metrolist.shared.generated.resources.plugins_marketplace_compatibility
import com.metrolist.shared.generated.resources.plugins_marketplace_desktop
import com.metrolist.shared.generated.resources.plugins_marketplace_empty
import com.metrolist.shared.generated.resources.plugins_marketplace_incompatible
import com.metrolist.shared.generated.resources.plugins_marketplace_platform
import com.metrolist.shared.generated.resources.plugins_marketplace_source
import com.metrolist.shared.generated.resources.plugins_marketplace_summary
import com.metrolist.shared.generated.resources.plugins_marketplace_update
import com.metrolist.shared.generated.resources.plugins_registry_url
import com.metrolist.shared.generated.resources.refresh
import com.metrolist.shared.generated.resources.search
import com.metrolist.shared.settings.Settings
import dev.metrofuse.runtime.MarketplaceClient
import dev.metrofuse.runtime.MarketplaceEntry
import dev.metrofuse.runtime.PluginStatus
import dev.metrofuse.runtime.Runtime
import dev.metrofuse.runtime.SemVer
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource

private val marketplaceClient by lazy { MarketplaceClient() }

internal class PluginMarketplaceState(
    savedUrl: String,
    platform: String,
) {
    var registryUrl by mutableStateOf(savedUrl)
    var catalogUrl by mutableStateOf(savedUrl)
    var catalog by mutableStateOf(marketplaceClient.cached(savedUrl))
    var query by mutableStateOf("")
    var category by mutableStateOf<String?>(null)
    var platformFilter by mutableStateOf(platform.takeIf { it == "android" || it == "desktop" })
    var expandedId by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)

    suspend fun refresh() {
        val url = registryUrl.trim()
        error = null
        try {
            catalog = marketplaceClient.fetch(url)
            catalogUrl = url
            Settings.shared.pluginMarketplaceUrl = url
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: failure.toString()
        }
    }
}

internal fun LazyListScope.pluginMarketplace(
    state: PluginMarketplaceState,
    runtime: Runtime,
    installedPlugins: List<PluginStatus>,
    busy: Boolean,
    operation: (suspend () -> Unit) -> Unit,
) {
    val catalog = state.catalog.takeIf { state.catalogUrl == state.registryUrl.trim() }
    val categories =
        catalog
            .orEmpty()
            .flatMap(MarketplaceEntry::displayCategories)
            .distinct()
            .sorted()
    val category = state.category?.takeIf { it in categories }
    val query = state.query.trim()
    val results =
        catalog.orEmpty().filter { entry ->
            val searchable = listOf(entry.name, entry.author, entry.id, entry.description) + entry.displayCategories
            (category == null || category in entry.displayCategories) &&
                (state.platformFilter?.let(entry::supports) != false) &&
                searchable.any { it.contains(query, ignoreCase = true) }
        }
    item {
        PluginSurface {
            Text(stringResource(Res.string.plugins_marketplace), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(Res.string.plugins_marketplace_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                state.registryUrl,
                {
                    state.registryUrl = it
                    state.error = null
                },
                label = { Text(stringResource(Res.string.plugins_registry_url)) },
                enabled = !busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(enabled = !busy && state.registryUrl.isNotBlank(), onClick = { operation { state.refresh() } }) {
                Text(stringResource(if (catalog == null) Res.string.plugins_fetch else Res.string.refresh))
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (catalog != null) {
                OutlinedTextField(
                    state.query,
                    { state.query = it },
                    label = { Text(stringResource(Res.string.search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MarketplaceFilter(
                    label = stringResource(Res.string.plugins_marketplace_platform),
                    selected = state.platformFilter,
                    options =
                        listOf(
                            "android" to stringResource(Res.string.plugins_marketplace_android),
                            "desktop" to stringResource(Res.string.plugins_marketplace_desktop),
                        ),
                    onSelect = { state.platformFilter = it },
                )
                if (categories.isNotEmpty()) {
                    MarketplaceFilter(
                        label = stringResource(Res.string.plugins_marketplace_category),
                        selected = category,
                        options = categories.map { it to it },
                        onSelect = { state.category = it },
                    )
                }
                if (results.isEmpty()) Text(stringResource(Res.string.plugins_marketplace_empty))
            }
        }
    }
    items(results, key = { "marketplace:${it.id}" }) { entry ->
        val installedVersion = installedPlugins.firstOrNull { it.manifest.id == entry.id }?.manifest?.version
        val hasUpdate = installedVersion?.let { SemVer.parse(entry.version) > SemVer.parse(it) } == true
        val artifact = entry.artifact(runtime.platform)
        var error by remember(entry.id, entry.version) { mutableStateOf<String?>(null) }
        PluginSurface {
            Text(entry.name, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(Res.string.plugins_author_version, entry.author, entry.version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entry.description.isNotBlank()) {
                Text(
                    entry.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val expanded = state.expandedId == entry.id
            TextButton(onClick = { state.expandedId = if (expanded) null else entry.id }) {
                Text(stringResource(if (expanded) Res.string.close else Res.string.details))
            }
            if (expanded) MarketplaceDetails(entry, artifact?.fileName)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when {
                artifact == null -> {
                    Text(stringResource(Res.string.plugins_marketplace_incompatible))
                }

                installedVersion == null || hasUpdate -> {
                    Button(enabled = !busy, onClick = {
                        operation {
                            error = null
                            try {
                                marketplaceClient.install(runtime, entry)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: failure.toString()
                            }
                        }
                    }) {
                        Text(stringResource(if (hasUpdate) Res.string.plugins_marketplace_update else Res.string.plugins_install))
                    }
                }

                else -> {
                    Text(stringResource(Res.string.plugins_installed))
                }
            }
        }
    }
}

@Composable
private fun MarketplaceFilter(
    label: String,
    selected: String?,
    options: List<Pair<String, String>>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val choices = listOf(null to stringResource(Res.string.all)) + options
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(choices.first { it.first == selected }.second)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { (id, title) ->
                    DropdownMenuItem(text = { Text(title) }, onClick = {
                        expanded = false
                        onSelect(id)
                    })
                }
            }
        }
    }
}

@Composable
private fun MarketplaceDetails(
    entry: MarketplaceEntry,
    fileName: String?,
) {
    if (entry.displayCategories.isNotEmpty()) Text(entry.displayCategories.joinToString())
    val platforms =
        buildList {
            if (entry.supports("android")) add(stringResource(Res.string.plugins_marketplace_android))
            if (entry.supports("desktop")) add(stringResource(Res.string.plugins_marketplace_desktop))
        }
    Text(platforms.joinToString())
    Text(stringResource(Res.string.plugins_marketplace_compatibility, entry.pluginApiVersion))
    fileName?.let { Text(it) }
    entry.sourceUrl?.let { url ->
        val uriHandler = LocalUriHandler.current
        TextButton(onClick = { uriHandler.openUri(url) }) { Text(stringResource(Res.string.plugins_marketplace_source)) }
    }
}
