package com.metrolist.shared.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.plugins_apply
import com.metrolist.shared.generated.resources.plugins_screen_unavailable
import com.metrolist.shared.generated.resources.plugins_title
import com.metrolist.shared.generated.resources.plugins_unsupported_ui
import com.metrolist.shared.ui.navigation.Screen
import dev.metrofuse.api.Layer
import dev.metrofuse.api.UiContext
import dev.metrofuse.api.UiContribution
import dev.metrofuse.api.UiNode
import dev.metrofuse.api.UiTarget
import dev.metrofuse.runtime.Runtime
import org.jetbrains.compose.resources.stringResource

internal const val PLUGIN_SCREEN_PREFIX = "plugin:"
internal const val PLUGIN_SCREEN_POINT_PREFIX = "screen."

internal data class PluginScreenRoute(val owner: String, val point: String) {
    val hasValidNames: Boolean
        get() = OWNER_PATTERN.matches(owner) && ".." !in owner && SCREEN_POINT_PATTERN.matches(point)

    fun toDestination(): String = "$PLUGIN_SCREEN_PREFIX$owner:$point"

    companion object {
        private val OWNER_PATTERN = Regex("[a-z][a-z0-9.-]{2,79}")
        private val SCREEN_POINT_PATTERN = Regex("screen\\.[a-zA-Z0-9_.-]{1,113}")
    }
}

@Composable
fun PluginDestinationScreen(
    route: Screen.PluginDestination,
    onBack: (() -> Unit)? = null,
) {
    val runtime = MetroFuseHost.runtime
    val registry by runtime.registry.state.collectAsState()
    val plugins by runtime.plugins.collectAsState()
    val contributions = registry.ui.filter { it.owner == route.owner && it.point == route.point }
    val pluginName = plugins.firstOrNull { it.manifest.id == route.owner }?.manifest?.name
        ?: stringResource(Res.string.plugins_title)

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onBack != null) {
            TextButton(onClick = onBack) { Text(stringResource(Res.string.back)) }
        }
        Text(pluginName, style = MaterialTheme.typography.headlineSmall)
        if (contributions.isEmpty()) {
            Text(stringResource(Res.string.plugins_screen_unavailable))
        } else {
            Column(Modifier.weight(1f)) {
                contributions.sortedBy { if (it.layer == Layer.BEFORE) -1 else it.layer.ordinal }.forEach { contribution ->
                    if (contribution.tree != null) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            PluginContributionContent(contribution, runtime)
                        }
                    } else {
                        PluginContributionContent(contribution, runtime)
                    }
                }
            }
        }
    }
}

@Composable
fun PluginUiSlot(point: String, fallback: @Composable () -> Unit = {}) {
    if (!MetroFuseHost.isConfigured) {
        fallback()
        return
    }
    val runtime = MetroFuseHost.runtime
    val registry by runtime.registry.state.collectAsState()
    val contributions = registry.ui.filter { it.point == point }
    if (contributions.isEmpty()) {
        fallback()
        return
    }
    val owners = runtime.registry.order(point, contributions.map(UiContribution::owner))
    val ordered = owners.flatMap { owner -> contributions.filter { it.owner == owner } }
    ordered.filter { it.layer == Layer.BEFORE }.forEach { PluginContributionContent(it, runtime) }
    val replacement = ordered.firstOrNull { it.layer == Layer.REPLACE }
    if (replacement == null) fallback() else PluginContributionContent(replacement, runtime)
    ordered.filter { it.layer == Layer.AFTER }.forEach { PluginContributionContent(it, runtime) }
}

@Composable
internal fun PluginContributionContent(contribution: UiContribution, runtime: Runtime, target: UiTarget? = null) {
    key(contribution.owner, contribution.point, contribution.layer, runtime.sessionToken(contribution.owner), target) {
        contribution.native?.Content(UiContext(runtime.player, runtime.metadata, runtime.host.navigation::navigate, target = target))
        contribution.tree?.let { PluginNode(it, contribution.owner, runtime, target) }
    }
}

@Composable
private fun PluginNode(node: UiNode, owner: String, runtime: Runtime, target: UiTarget?) {
    val token = runtime.sessionToken(owner)
    val track by runtime.metadata.collectAsState()
    fun text(name: String): String = when (val value = node.props[name].orEmpty()) {
        "$" + "title" -> track.title
        "$" + "artist" -> track.artist
        "$" + "album" -> track.album
        "$" + "targetId" -> target?.id.orEmpty()
        "$" + "targetTitle" -> target?.title.orEmpty()
        "$" + "targetKind" -> target?.kind.orEmpty()
        else -> value
    }
    fun action(value: String = "") {
        val name = node.props["action"] ?: return
        runtime.action(owner, name, value, token, target)
    }
    when (node.type) {
        "Text" -> Text(text("text"))
        "Column", "List", "Section" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { node.children.forEach { key(it.id) { PluginNode(it, owner, runtime, target) } } }
        "Row" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { node.children.forEach { key(it.id) { PluginNode(it, owner, runtime, target) } } }
        "Button" -> Button(onClick = { action() }) { Text(text("text").ifEmpty { text("label") }) }
        "Dialog" -> {
            var open by remember { mutableStateOf(false) }
            Button(onClick = { open = true }) { Text(text("label")) }
            if (open) {
                AlertDialog(
                    onDismissRequest = { open = false },
                    title = { Text(text("title")) },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            node.children.forEach { key(it.id) { PluginNode(it, owner, runtime, target) } }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { open = false }) { Text(stringResource(Res.string.close)) }
                    },
                )
            }
        }
        "Switch" -> {
            var checked by remember(node.id, node.props["checked"]) { mutableStateOf(node.props["checked"] == "true") }
            Row {
                Text(text("label"), Modifier.weight(1f))
                Switch(checked, { checked = it; action(it.toString()) })
            }
        }
        "TextField", "Input" -> {
            var value by remember(node.id, node.props["value"]) { mutableStateOf(node.props["value"].orEmpty()) }
            OutlinedTextField(value, { value = it }, label = { Text(text("label")) }, singleLine = true)
            TextButton(onClick = { action(value) }) { Text(stringResource(Res.string.plugins_apply)) }
        }
        else -> {
            val component = runtime.registry.component(node.type)
            if (component != null) component.Content(node, UiContext(runtime.player, runtime.metadata, runtime.host.navigation::navigate, target = target))
            else Text(stringResource(Res.string.plugins_unsupported_ui, node.type))
        }
    }
}
