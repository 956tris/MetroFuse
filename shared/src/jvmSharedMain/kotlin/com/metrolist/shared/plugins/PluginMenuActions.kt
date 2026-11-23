package com.metrolist.shared.plugins

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.metrofuse.api.UiContribution
import dev.metrofuse.runtime.Runtime
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.icons.MetrolistIcons
import dev.metrofuse.api.UiTarget
import org.jetbrains.compose.resources.stringResource

/** Uses the same registered Compose/declarative components in platform action menus. */
@Composable
fun pluginMenuActions(point: String, onDismiss: () -> Unit, target: UiTarget? = null): List<PlayerMenuAction> {
    if (!MetroFuseHost.isConfigured) return emptyList()
    val runtime = MetroFuseHost.runtime
    val registry by runtime.registry.state.collectAsState()
    val plugins by runtime.plugins.collectAsState()
    val contributions = registry.ui.filter { it.point == point }
    var selected by remember(point, target) { mutableStateOf<String?>(null) }
    val current = contributions.firstOrNull { "${it.owner}:${it.layer}" == selected }
    LaunchedEffect(current, selected) {
        if (selected != null && current == null) { selected = null; onDismiss() }
    }
    current?.let { contribution ->
        Dialog(onDismissRequest = { selected = null; onDismiss() }) {
            PluginContentDialogContent(
                contribution = contribution,
                runtime = runtime,
                target = target,
                onClose = { selected = null; onDismiss() },
            )
        }
    }
    return contributions.map { contribution ->
        PlayerMenuAction(
            icon = MetrolistIcons.Outlined.Settings,
            title = contribution.tree?.props?.get("title")
                ?: plugins.firstOrNull { it.manifest.id == contribution.owner }?.manifest?.name
                ?: contribution.owner,
            onClick = { selected = "${contribution.owner}:${contribution.layer}" },
        )
    }
}

@Composable
internal fun PluginContentDialogContent(
    contribution: UiContribution,
    runtime: Runtime,
    target: UiTarget? = null,
    onClose: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(20.dp)) {
            Column(Modifier.weight(1f, fill = false)) {
                PluginContributionContent(contribution, runtime, target)
            }
            TextButton(onClick = onClose) { Text(stringResource(Res.string.close)) }
        }
    }
}
