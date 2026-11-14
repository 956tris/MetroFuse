package com.metrolist.shared.plugins

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.home
import com.metrolist.shared.generated.resources.music_frontend_offline
import com.metrolist.shared.generated.resources.music_frontend_switch
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.stringResource

@Composable
fun MusicFrontendSelector(navigationContentBounds: Rect? = null) {
    val frontends by PluginServicesStore.frontends.collectAsState()
    val selection = effectiveHomeSelection()
    val selectedTitle =
        frontends.firstOrNull { HomeSelection.Plugin(it.owner) == selection }?.title
            ?: stringResource(Res.string.music_frontend_offline)
    var expanded by remember { mutableStateOf(false) }
    var anchorBounds by remember { mutableStateOf(Rect.Zero) }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // Desktop's title bar extends over the rail; keep the anchored menu inside the content pane.
    val menuOffset =
        with(density) {
            val startOffset =
                navigationContentBounds?.let { content ->
                    if (layoutDirection == LayoutDirection.Ltr) {
                        content.left - anchorBounds.left
                    } else {
                        anchorBounds.right - content.right
                    }
                } ?: 0f
            DpOffset(startOffset.coerceAtLeast(0f).toDp(), 4.dp)
        }
    val menuMaxWidth =
        with(density) {
            navigationContentBounds?.width?.toDp()?.coerceAtMost(300.dp) ?: 300.dp
        }
    LaunchedEffect(frontends) { if (frontends.isEmpty()) expanded = false }
    Box(Modifier.onGloballyPositioned { anchorBounds = it.boundsInWindow() }) {
        TextButton(onClick = { if (frontends.isNotEmpty()) expanded = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.widthIn(max = 220.dp)) {
                    Text(stringResource(Res.string.home), style = MaterialTheme.typography.titleLarge)
                    Text(selectedTitle, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (frontends.isNotEmpty()) {
                    Icon(MetrolistIcons.Outlined.KeyboardArrowDown, stringResource(Res.string.music_frontend_switch))
                }
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = menuOffset,
            modifier = Modifier.widthIn(min = minOf(200.dp, menuMaxWidth), max = menuMaxWidth).heightIn(max = 400.dp),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.music_frontend_offline), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = { if (selection == HomeSelection.Offline) Icon(MetrolistIcons.Default.Check, null) },
                onClick = {
                    selectHomeSource(HomeSelection.Offline)
                    expanded = false
                },
            )
            frontends.forEach { frontend ->
                DropdownMenuItem(
                    text = { Text(frontend.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = {
                        if (frontend.icon == null) {
                            Icon(MetrolistIcons.Outlined.Extension, null, Modifier.size(24.dp))
                        } else {
                            AsyncImage(frontend.icon, null, Modifier.size(24.dp))
                        }
                    },
                    trailingIcon = { if (selection == HomeSelection.Plugin(frontend.owner)) Icon(MetrolistIcons.Default.Check, null) },
                    onClick = {
                        selectHomeSource(HomeSelection.Plugin(frontend.owner))
                        expanded = false
                    },
                )
            }
        }
    }
}
