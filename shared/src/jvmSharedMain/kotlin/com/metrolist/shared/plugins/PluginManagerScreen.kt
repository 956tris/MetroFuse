@file:Suppress("ktlint:standard:no-wildcard-imports")

package com.metrolist.shared.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.shared.generated.resources.*
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.ExpressiveSwitch
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.settings.LocalSettingsPageBottomPadding
import com.metrolist.shared.ui.settings.SettingsNavigationRow
import dev.metrofuse.api.PluginSettings
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.SettingDefinition
import dev.metrofuse.api.SettingType
import dev.metrofuse.api.Track
import dev.metrofuse.runtime.LifecyclePhase
import dev.metrofuse.runtime.PluginStatus
import dev.metrofuse.runtime.Runtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.stringResource
import java.io.File

@Composable
fun PluginManagerScreen(
    onImport: () -> Unit,
    importError: String? = null,
    importInProgress: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val runtime = MetroFuseHost.runtime
    val plugins by runtime.plugins.collectAsState()
    val registry by runtime.registry.state.collectAsState()
    val recovery by runtime.recovery.collectAsState()
    val logs by runtime.logs.collectAsState()
    val scope = rememberCoroutineScope()
    var operationInProgress by remember { mutableStateOf(false) }
    val busy = operationInProgress || importInProgress
    var error by remember { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableStateOf(0) }
    var advancedSection by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<Track>>(emptyList()) }
    val marketplace =
        remember(runtime.platform) {
            PluginMarketplaceState(Settings.shared.pluginMarketplaceUrl, runtime.platform)
        }

    fun operation(block: suspend () -> Unit) {
        if (busy) return
        operationInProgress = true
        error = null
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: failure.toString()
            } finally {
                operationInProgress = false
            }
        }
    }
    LaunchedEffect(runtime) { runtime.initialize() }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
            ScrollableTabRow(
                selectedTabIndex = section,
                edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.surface,
            ) {
                listOf(Res.string.plugins_installed, Res.string.plugins_marketplace, Res.string.plugins_advanced).forEachIndexed {
                    index,
                    label,
                    ->
                    Tab(selected = section == index, onClick = { section = index }, text = { Text(stringResource(label)) })
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 20.dp,
                        bottom =
                            24.dp + LocalSettingsPageBottomPadding.current,
                    ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                (error ?: importError)?.let { message ->
                    item { PluginNotice(message, isError = true) }
                }
                if (recovery) item { PluginNotice(stringResource(Res.string.plugins_recovery), isError = true) }
                when (section) {
                    0 -> {
                        item {
                            PluginImportCard(onImport, !busy)
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(Res.string.plugins_your_plugins), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(
                                        Res.string.plugins_installed_summary,
                                        plugins.count { it.phase == LifecyclePhase.ACTIVE },
                                        plugins.size,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (plugins.isEmpty()) {
                            item {
                                PluginSurface {
                                    Icon(
                                        MetrolistIcons.Outlined.Extension,
                                        null,
                                        Modifier.size(40.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(stringResource(Res.string.plugins_empty_title), style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        stringResource(Res.string.plugins_empty_summary),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        items(plugins, key = { it.manifest.id }) { plugin ->
                            InstalledPluginCard(runtime, plugin, busy, ::operation)
                        }
                        item {
                            Text(
                                stringResource(Res.string.plugins_trust),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    1 -> {
                        pluginMarketplace(marketplace, runtime, plugins, busy, ::operation)
                    }

                    else -> {
                        item {
                            Text(
                                stringResource(Res.string.plugins_advanced_summary),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                listOf(
                                    Triple("providers", Res.string.plugins_providers, MetrolistIcons.Outlined.Tune),
                                    Triple("search", Res.string.plugins_search, MetrolistIcons.Outlined.Search),
                                    Triple("diagnostics", Res.string.plugins_diagnostics, MetrolistIcons.Outlined.Info),
                                ).forEachIndexed { index, (id, label, icon) ->
                                    val expanded = advancedSection == id
                                    SettingsNavigationRow(
                                        title = stringResource(label),
                                        icon = icon,
                                        first = index == 0,
                                        last = index == 2,
                                        onClick = { advancedSection = if (expanded) null else id },
                                        trailingContent = {
                                            Icon(
                                                if (expanded) {
                                                    MetrolistIcons.Outlined.KeyboardArrowUp
                                                } else {
                                                    MetrolistIcons.Outlined.KeyboardArrowDown
                                                },
                                                null,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        when (advancedSection) {
                            "providers" -> {
                                val providers =
                                    runtime.registry
                                        .audioSources()
                                        .filter { it.owner != "builtin" }
                                        .map { "audio.resolve" to it.id } +
                                        runtime.registry.metadataSources().map { "metadata.resolve" to it.id } +
                                        runtime.registry.lyricsProviders().map { "lyrics.resolve" to it.id } +
                                        runtime.registry.searchProviders().map { "search.resolve" to it.id }
                                item {
                                    Text(
                                        stringResource(Res.string.plugins_priority_summary),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (providers.isEmpty()) {
                                        Text(
                                            stringResource(Res.string.plugins_no_providers),
                                            Modifier.padding(top = 12.dp),
                                        )
                                    }
                                }
                                items(providers, key = { "${it.first}:${it.second}" }) { (point, id) ->
                                    val priority = registry.pointPriorities[point]?.get(id) ?: 0
                                    PluginSurface {
                                        Text(id, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            stringResource(
                                                when (point) {
                                                    "audio.resolve" -> Res.string.plugins_provider_audio
                                                    "metadata.resolve" -> Res.string.plugins_provider_metadata
                                                    "lyrics.resolve" -> Res.string.plugins_provider_lyrics
                                                    else -> Res.string.plugins_provider_search
                                                },
                                            ),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        PluginPriority(priority, !busy) { runtime.registry.pointPriority(point, id, it) }
                                    }
                                }
                            }

                            "search" -> {
                                item {
                                    PluginSurface {
                                        OutlinedTextField(query, {
                                            query = it
                                        }, label = {
                                            Text(
                                                stringResource(Res.string.plugins_query),
                                            )
                                        }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                        Button(enabled = !busy && query.isNotBlank(), onClick = {
                                            operation {
                                                searchResults =
                                                    runtime.pipelines.search(SearchRequest(query)).tracks
                                            }
                                        }) {
                                            Icon(MetrolistIcons.Outlined.Search, null, Modifier.size(18.dp))
                                            Text(stringResource(Res.string.search), Modifier.padding(start = 8.dp))
                                        }
                                    }
                                }
                                items(searchResults, key = { it.id }) { track ->
                                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                                        Row(
                                            Modifier.fillMaxWidth().padding(16.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(track.title, style = MaterialTheme.typography.bodyLarge)
                                                Text(
                                                    track.artist,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            TextButton(
                                                onClick = { operation { MetroFuseHost.playTrack(track) } },
                                                enabled = !busy,
                                            ) { Text(stringResource(Res.string.plugins_play)) }
                                        }
                                    }
                                }
                            }

                            "diagnostics" -> {
                                if (logs.isEmpty()) item { PluginNotice(stringResource(Res.string.plugins_no_diagnostics)) }
                                items(logs.takeLast(30)) { line ->
                                    Text(
                                        line,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginImportCard(
    onImport: () -> Unit,
    enabled: Boolean,
) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(MetrolistIcons.Outlined.Extension, null, Modifier.size(28.dp))
                Text(stringResource(Res.string.plugins_import_title), style = MaterialTheme.typography.titleLarge)
            }
            Text(stringResource(Res.string.plugins_import_summary), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onImport, enabled = enabled) {
                Icon(MetrolistIcons.Filled.Add, null, Modifier.size(18.dp))
                Text(stringResource(Res.string.plugins_import), Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
internal fun PluginSurface(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun PluginNotice(
    message: String,
    isError: Boolean = false,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Text(message, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InstalledPluginCard(
    runtime: Runtime,
    plugin: PluginStatus,
    busy: Boolean,
    operation: (suspend () -> Unit) -> Unit,
) {
    val manifest = plugin.manifest
    val registry by runtime.registry.state.collectAsState()
    var menuOpen by remember(manifest.id) { mutableStateOf(false) }
    var showDetails by rememberSaveable(manifest.id) { mutableStateOf(false) }
    var showSettings by rememberSaveable(manifest.id) { mutableStateOf(false) }
    val contributions = registry.ui.filter { it.owner == manifest.id }
    val settings = contributions.filter { it.point.startsWith("settings.") || it.point.endsWith(".settings") }
    val enabled = plugin.phase == LifecyclePhase.ACTIVE
    val toggleLabel = stringResource(if (enabled) Res.string.plugins_disable else Res.string.plugins_enable)
    PluginSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val icon =
                manifest.icon?.let { File(plugin.directory, it) }?.takeIf {
                    it.canonicalPath.startsWith(plugin.directory.canonicalPath + File.separator) && it.isFile
                }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                if (icon == null) {
                    Icon(MetrolistIcons.Outlined.Extension, null, Modifier.padding(12.dp).size(24.dp))
                } else {
                    AsyncImage(icon, contentDescription = null, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)))
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(manifest.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(Res.string.plugins_author_version, manifest.author, manifest.version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }, enabled = !busy) {
                    Icon(MetrolistIcons.Outlined.MoreVert, stringResource(Res.string.plugins_actions))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (manifest.settings.isNotEmpty() || settings.isNotEmpty()) {
                        DropdownMenuItem(text = { Text(stringResource(Res.string.plugins_settings)) }, onClick = {
                            menuOpen = false
                            showSettings =
                                !showSettings
                        })
                    }
                    DropdownMenuItem(text = { Text(stringResource(Res.string.details)) }, onClick = {
                        menuOpen = false
                        showDetails =
                            !showDetails
                    })
                    DropdownMenuItem(text = { Text(stringResource(Res.string.plugins_reload)) }, enabled = enabled, onClick = {
                        menuOpen =
                            false
                        ; operation { runtime.hotReplace(manifest.id) }
                    })
                    DropdownMenuItem(text = {
                        Text(
                            stringResource(Res.string.plugins_uninstall),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }, onClick = {
                        menuOpen =
                            false
                        ; operation { runtime.uninstall(manifest.id) }
                    })
                }
            }
        }
        if (manifest.description.isNotBlank()) {
            Text(
                manifest.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(
                        when (plugin.phase) {
                            LifecyclePhase.ACTIVE -> Res.string.plugins_active
                            LifecyclePhase.FAILED -> Res.string.plugins_failed
                            LifecyclePhase.DISABLED, LifecyclePhase.UNLOADED -> Res.string.plugins_disabled
                            LifecyclePhase.LOADING -> Res.string.plugins_loading
                            else -> Res.string.plugins_installed
                        },
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(
                        if (manifest.runtime ==
                            "javascript"
                        ) {
                            Res.string.plugins_javascript
                        } else {
                            Res.string.plugins_native
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ExpressiveSwitch(
                checked = enabled,
                onCheckedChange = { active ->
                    operation { if (active) runtime.enableOrdered(setOf(manifest.id)) else runtime.disable(manifest.id) }
                },
                enabled = !busy && plugin.phase != LifecyclePhase.LOADING,
                modifier = Modifier.semantics { contentDescription = toggleLabel },
            )
        }
        plugin.error?.let { PluginNotice(it, isError = true) }
        if (showDetails) {
            Text(manifest.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(manifest.platforms.joinToString(), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(Res.string.plugins_marketplace_compatibility, manifest.api), style = MaterialTheme.typography.bodySmall)
            manifest.minimumHostVersion?.let {
                Text(stringResource(Res.string.plugins_minimum_host_version, it), style = MaterialTheme.typography.bodySmall)
            }
            if (manifest.capabilities.isNotEmpty()) {
                Text(
                    stringResource(Res.string.plugins_capabilities, manifest.capabilities.joinToString()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (manifest.dependencies.isNotEmpty()) {
                Text(
                    stringResource(
                        Res.string.plugins_dependencies,
                        manifest.dependencies.entries.sortedBy { it.key }.joinToString { (id, version) ->
                            "$id $version"
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            PluginPriority(registry.priorities[manifest.id] ?: 0, !busy) { runtime.registry.priority(manifest.id, it) }
        }
        if (showSettings) {
            Text(stringResource(Res.string.plugins_settings), style = MaterialTheme.typography.titleSmall)
            PluginDefinedSettings(runtime, manifest.id, manifest.version, manifest.settings, busy, operation)
            settings.forEach { PluginContributionContent(it, runtime) }
        }
        contributions.filter { it.point.startsWith(PLUGIN_SCREEN_POINT_PREFIX) }.distinctBy { it.point }.forEach { contribution ->
            val route = PluginScreenRoute(manifest.id, contribution.point)
            val title = contribution.tree?.props?.get("title") ?: manifest.name
            TextButton(onClick = { runtime.host.navigation.navigate(route.toDestination()) }) {
                Text(stringResource(Res.string.plugins_open_screen, title))
            }
        }
    }
}

@Composable
private fun PluginPriority(
    priority: Int,
    enabled: Boolean,
    onChange: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.plugins_priority, priority), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        IconButton(enabled = enabled && priority > -1000, onClick = { onChange(priority - 1) }) {
            Icon(MetrolistIcons.Outlined.KeyboardArrowDown, stringResource(Res.string.plugins_priority_down))
        }
        IconButton(enabled = enabled && priority < 1000, onClick = { onChange(priority + 1) }) {
            Icon(MetrolistIcons.Outlined.KeyboardArrowUp, stringResource(Res.string.plugins_priority_up))
        }
    }
}

@Composable
private fun PluginDefinedSettings(
    runtime: Runtime,
    id: String,
    version: String,
    definitions: List<SettingDefinition>,
    busy: Boolean,
    operation: (suspend () -> Unit) -> Unit,
) {
    var settings by remember(id, version, definitions) { mutableStateOf<PluginSettings?>(null) }
    LaunchedEffect(id, version, definitions) { settings = runtime.settingsFor(id) }
    val store = settings ?: return
    val values by store.values.collectAsState()
    definitions.forEach { definition ->
        val value = values[definition.key] ?: definition.default
        if (definition.type == SettingType.BOOLEAN) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(definition.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                ExpressiveSwitch(value.jsonPrimitive.booleanOrNull == true, { checked ->
                    operation {
                        store.set(definition.key, JsonPrimitive(checked))
                    }
                }, enabled = !busy)
            }
        } else {
            var input by remember(definition.key, value) {
                mutableStateOf(
                    if (definition.type ==
                        SettingType.OBJECT
                    ) {
                        value.toString()
                    } else {
                        value.jsonPrimitive.content
                    },
                )
            }
            OutlinedTextField(input, {
                input = it
            }, enabled = !busy, modifier = Modifier.fillMaxWidth(), label = { Text(definition.title) }, supportingText = {
                if (definition.options.isNotEmpty()) Text(definition.options.joinToString())
            })
            TextButton(enabled = !busy, onClick = {
                operation {
                    val next =
                        when (definition.type) {
                            SettingType.NUMBER -> JsonPrimitive(input.toDouble())
                            SettingType.OBJECT -> Json.parseToJsonElement(input)
                            else -> JsonPrimitive(input)
                        }
                    store.set(definition.key, next)
                }
            }) { Text(stringResource(Res.string.plugins_save)) }
        }
    }
}
