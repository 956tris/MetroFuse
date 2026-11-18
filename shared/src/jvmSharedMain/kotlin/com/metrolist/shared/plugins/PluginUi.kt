package com.metrolist.shared.plugins

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.plugins_apply
import com.metrolist.shared.generated.resources.plugins_screen_unavailable
import com.metrolist.shared.generated.resources.plugins_title
import com.metrolist.shared.generated.resources.plugins_unsupported_ui
import com.metrolist.shared.generated.resources.plugins_use_original_ui
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.LocalNavigator
import com.metrolist.shared.ui.navigation.Screen
import dev.metrofuse.api.Layer
import dev.metrofuse.api.Track
import dev.metrofuse.api.UiArtworkEnvironment
import dev.metrofuse.api.UiContext
import dev.metrofuse.api.UiContribution
import dev.metrofuse.api.UiNode
import dev.metrofuse.api.UiPluginState
import dev.metrofuse.api.UiTarget
import dev.metrofuse.runtime.Runtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

internal const val PLUGIN_SCREEN_PREFIX = "plugin:"
internal const val PLUGIN_SCREEN_POINT_PREFIX = "screen."

internal data class PluginScreenRoute(
    val owner: String,
    val point: String,
) {
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
    val recovery by runtime.recovery.collectAsState()
    val safeMode by runtime.uiSafeMode.collectAsState()
    val contributions = if (recovery || safeMode) emptyList() else registry.ui.filter { it.owner == route.owner && it.point == route.point }
    val pluginName =
        plugins.firstOrNull { it.manifest.id == route.owner }?.manifest?.name
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
                    if (contribution.tree?.type in setOf("Column", "Section", "List")) {
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
fun PluginAppShell(
    original: @Composable () -> Unit,
    onCustomUiLaidOut: () -> Unit = {},
) {
    if (!MetroFuseHost.isConfigured) {
        original()
        return
    }
    val runtime = MetroFuseHost.runtime
    // Settings, recovery and detail routes must remain reachable from a custom shell.
    val route = LocalNavigator.current.state.currentRoute
    if (route != Screen.Home && route != Screen.Search && route != Screen.Library) {
        original()
        return
    }
    val registry by runtime.registry.state.collectAsState()
    val safeMode by runtime.uiSafeMode.collectAsState()
    val recovery by runtime.recovery.collectAsState()
    val customShell =
        remember(registry, safeMode, recovery) {
            !safeMode && !recovery && runtime.registry.layers("ui.app").any { it.layer == Layer.REPLACE }
        }
    val overlays = pluginLayers("ui.overlay")
    if (!customShell && overlays.isEmpty()) {
        PluginUiSlot("ui.app", original)
        return
    }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .fillMaxSize()
            .then(if (customShell) Modifier.safeDrawingPadding() else Modifier)
            .onGloballyPositioned { if (customShell) onCustomUiLaidOut() },
    ) {
        PluginUiSlot("ui.app", original)
        overlays.forEach { PluginContributionContent(it, runtime) }
        if (customShell) {
            IconButton(
                onClick = { scope.launch { runtime.setUiSafeMode(true) } },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(40.dp),
                colors =
                    IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.2f),
                        contentColor = Color.White.copy(alpha = 0.6f),
                    ),
            ) {
                Icon(
                    MetrolistIcons.Outlined.SettingsBackupRestore,
                    contentDescription = stringResource(Res.string.plugins_use_original_ui),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
fun PluginNavigationLayout(
    content: @Composable () -> Unit,
    original: @Composable () -> Unit,
) {
    val layers = pluginLayers("ui.navigation")
    if (layers.isEmpty()) {
        original()
        return
    }
    val runtime = MetroFuseHost.runtime
    Column(Modifier.fillMaxSize()) {
        layers.filter { it.layer == Layer.BEFORE }.forEach { PluginContributionContent(it, runtime) }
        val replacement = layers.firstOrNull { it.layer == Layer.REPLACE }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (replacement == null) original() else content()
        }
        replacement?.let { PluginContributionContent(it, runtime) }
        layers.filter { it.layer == Layer.AFTER }.forEach { PluginContributionContent(it, runtime) }
    }
}

@Composable
private fun pluginLayers(point: String): List<UiContribution> {
    if (!MetroFuseHost.isConfigured) return emptyList()
    val runtime = MetroFuseHost.runtime
    val state by runtime.registry.state.collectAsState()
    val recovery by runtime.recovery.collectAsState()
    val safeMode by runtime.uiSafeMode.collectAsState()
    return remember(state, point, recovery, safeMode) {
        if (recovery || safeMode) emptyList() else runtime.registry.layers(point)
    }
}

@Composable
fun PluginUiSlot(
    point: String,
    fallback: @Composable () -> Unit = {},
) {
    val layers = pluginLayers(point)
    if (layers.isEmpty()) {
        fallback()
        return
    }
    val runtime = MetroFuseHost.runtime
    layers.filter { it.layer == Layer.BEFORE }.forEach { PluginContributionContent(it, runtime) }
    val replacement = layers.firstOrNull { it.layer == Layer.REPLACE }
    if (replacement == null) fallback() else PluginContributionContent(replacement, runtime)
    layers.filter { it.layer == Layer.AFTER }.forEach { PluginContributionContent(it, runtime) }
}

@Composable
internal fun PluginContributionContent(
    contribution: UiContribution,
    runtime: Runtime,
    target: UiTarget? = null,
) {
    key(contribution.owner, contribution.point, contribution.layer, runtime.sessionToken(contribution.owner), target) {
        if (contribution.native != null || contribution.tree != null) {
            DisposableEffect(runtime, contribution.owner) {
                val release = runtime.watchUi(contribution.owner)
                onDispose { release() }
            }
        }
        val extensions =
            remember {
                object : UiArtworkEnvironment {
                    override val platform = runtime.platform
                    override val homeShelves =
                        combine(runtime.homes.pages, musicFrontendOwnerChanges()) { pages, owner ->
                            pages[owner]?.shelves.orEmpty()
                        }

                    @Composable
                    override fun Artwork(
                        url: String,
                        description: String?,
                        modifier: Modifier,
                    ) {
                        PluginArtwork(url, description, modifier)
                    }

                    override val destination = runtime.host.navigation.destination
                    override val pluginStates =
                        runtime.plugins.map { plugins ->
                            plugins.map { UiPluginState(it.manifest.id, it.manifest.name, it.state) }
                        }

                    override fun library(
                        offset: Int,
                        limit: Int,
                    ) = MetroFuseHost.library(offset, limit)

                    @Composable
                    override fun Render(
                        point: String,
                        fallback: @Composable () -> Unit,
                    ) {
                        require(point.startsWith("component.")) { "Use a component.* slot inside a plugin UI" }
                        PluginUiSlot(point, fallback)
                    }
                }
            }
        var failed by remember { mutableStateOf(false) }
        if (!failed) {
            val completeFirstRender = remember { runtime.beginUiRender() }

            fun reject(error: Throwable) {
                runtime.forceOriginalUi()
                // Recovery must survive disposal of the broken contribution's composition scope.
                runtime.scope.launch {
                    failed = true
                    runtime.failUi(contribution.owner, error)
                }
            }
            SubcomposeLayout { constraints ->
                try {
                    val children =
                        subcompose(Unit) {
                            contribution.native?.Content(
                                UiContext(runtime.player, runtime.metadata, runtime.host.navigation::navigate, extensions, target),
                            )
                            contribution.tree?.let {
                                val track by runtime.metadata.collectAsState()
                                PluginNode(it, contribution.owner, runtime, target, track)
                            }
                        }.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
                    val width = (children.maxOfOrNull { it.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
                    val height = (children.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
                    layout(width, height) {
                        try {
                            children.forEach { it.placeRelative(0, 0) }
                            completeFirstRender()
                        } catch (error: CancellationException) {
                            completeFirstRender()
                            throw error
                        } catch (error: Exception) {
                            reject(error)
                        } catch (error: LinkageError) {
                            reject(error)
                        }
                    }
                } catch (error: CancellationException) {
                    completeFirstRender()
                    throw error
                } catch (error: Exception) {
                    reject(error)
                    layout(constraints.minWidth, constraints.minHeight) {}
                } catch (error: LinkageError) {
                    reject(error)
                    layout(constraints.minWidth, constraints.minHeight) {}
                }
            }
        }
    }
}

@Composable
private fun PluginNode(
    node: UiNode,
    owner: String,
    runtime: Runtime,
    target: UiTarget?,
    track: Track,
    parentModifier: Modifier = Modifier,
) {
    val token = runtime.sessionToken(owner)

    fun text(name: String): String =
        when (val value = node.props[name].orEmpty()) {
            "$" + "title" -> track.title
            "$" + "artist" -> track.artist
            "$" + "album" -> track.album
            "$" + "artwork" -> track.artwork
            "$" + "targetId" -> target?.id.orEmpty()
            "$" + "targetTitle" -> target?.title.orEmpty()
            "$" + "targetKind" -> target?.kind.orEmpty()
            else -> value
        }

    fun action(value: String = "") {
        val name = node.props["action"] ?: return
        runtime.action(owner, name, value, token, target)
    }
    val spacing = (node.props["spacing"]?.toIntOrNull()?.coerceIn(0, 64) ?: 8).dp
    val modifier =
        parentModifier
            .then(if (node.props["fillWidth"] == "true") Modifier.fillMaxWidth() else Modifier)
            .then(if (node.props["fillHeight"] == "true") Modifier.fillMaxHeight() else Modifier)
            .then(if (node.props["fillSize"] == "true") Modifier.fillMaxSize() else Modifier)
            .then(
                node.props["aspectRatio"]
                    ?.toFloatOrNull()
                    ?.takeIf { it.isFinite() && it in 0.1f..10f }
                    ?.let { Modifier.aspectRatio(it) }
                    ?: Modifier,
            ).then(
                node.props["width"]
                    ?.toIntOrNull()
                    ?.coerceIn(1, 1200)
                    ?.let { Modifier.width(it.dp) } ?: Modifier,
            ).then(
                node.props["height"]
                    ?.toIntOrNull()
                    ?.coerceIn(1, 1200)
                    ?.let { Modifier.height(it.dp) } ?: Modifier,
            ).then(
                node.props["radius"]
                    ?.toIntOrNull()
                    ?.coerceIn(0, 64)
                    ?.let { Modifier.clip(RoundedCornerShape(it.dp)) } ?: Modifier,
            ).then(node.props["background"]?.let(::pluginColor)?.let { Modifier.background(it) } ?: Modifier)
            .then(
                if (node.props["action"] != null && node.type in setOf("Box", "Column", "Row", "Artwork", "Text")) {
                    Modifier.clickable(
                        enabled = node.props["enabled"] != "false",
                        role = Role.Button,
                    ) { action(node.props["value"].orEmpty()) }
                } else {
                    Modifier
                },
            ).padding((node.props["padding"]?.toIntOrNull()?.coerceIn(0, 96) ?: 0).dp)
    val children: @Composable () -> Unit = {
        node.children.forEach { key(it.id) { PluginNode(it, owner, runtime, target, track) } }
    }
    when (node.type) {
        "Text" -> {
            Text(
                text("text"),
                modifier = modifier,
                color = node.props["color"]?.let(::pluginColor) ?: Color.Unspecified,
                fontWeight =
                    node.props["fontWeight"]
                        ?.toIntOrNull()
                        ?.coerceIn(100, 900)
                        ?.let(::FontWeight),
                maxLines = node.props["maxLines"]?.toIntOrNull()?.coerceIn(1, 20) ?: Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
                fontSize =
                    node.props["fontSize"]
                        ?.toIntOrNull()
                        ?.coerceIn(8, 96)
                        ?.sp ?: TextUnit.Unspecified,
            )
        }

        "Box" -> {
            Box(modifier) { children() }
        }

        "Adaptive" -> {
            BoxWithConstraints(modifier) {
                val breakpoint = (node.props["breakpoint"]?.toIntOrNull()?.coerceIn(480, 1600) ?: 720).dp
                val index = if (runtime.platform == "desktop" && maxWidth >= breakpoint) 0 else 1
                node.children.getOrNull(index)?.let { PluginNode(it, owner, runtime, target, track) }
            }
        }

        "Artwork" -> {
            PluginArtwork(text("url"), text("description").ifBlank { null }, modifier)
        }

        "Icon" -> {
            Icon(
                pluginUiIcon(text("icon")),
                text("description").ifBlank { null },
                modifier.size(
                    (
                        node.props["iconSize"]?.toIntOrNull()?.coerceIn(12, 64)
                            ?: 24
                    ).dp,
                ),
                tint = node.props["color"]?.let(::pluginColor) ?: MaterialTheme.colorScheme.onSurface,
            )
        }

        "IconButton" -> {
            IconButton(
                onClick = { action(node.props["value"].orEmpty()) },
                modifier = modifier,
                enabled = node.props["enabled"] != "false",
                colors =
                    IconButtonDefaults.iconButtonColors(
                        contentColor =
                            node.props["color"]?.let(::pluginColor) ?: MaterialTheme.colorScheme.onSurface,
                    ),
            ) {
                Icon(
                    pluginUiIcon(text("icon")),
                    text("description").ifBlank { null },
                    Modifier.size(
                        (
                            node.props["iconSize"]?.toIntOrNull()?.coerceIn(12, 64)
                                ?: 24
                        ).dp,
                    ),
                )
            }
        }

        "Spacer" -> {
            Spacer(modifier)
        }

        "AnimatedVisibility" -> {
            AnimatedVisibility(visible = node.props["visible"] != "false", modifier = modifier) { children() }
        }

        "LazyColumn" -> {
            LazyColumn(
                modifier,
                verticalArrangement = Arrangement.spacedBy(spacing),
                horizontalAlignment =
                    when (node.props["horizontalAlignment"]) {
                        "center" -> Alignment.CenterHorizontally
                        "end" -> Alignment.End
                        else -> Alignment.Start
                    },
            ) {
                items(node.children, key = UiNode::id) { PluginNode(it, owner, runtime, target, track) }
            }
        }

        "LazyRow" -> {
            LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(spacing)) {
                items(node.children, key = UiNode::id) { PluginNode(it, owner, runtime, target, track) }
            }
        }

        "Image" -> {
            val path = node.props["asset"].orEmpty()
            val bitmap by produceState<ImageBitmap?>(null, owner, token, path) {
                value =
                    try {
                        runtime.artwork(owner, path)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        null
                    }
            }
            bitmap?.let { Image(it, text("description").ifBlank { null }, modifier) }
        }

        "Slider" -> {
            val value =
                node.props["value"]
                    ?.toFloatOrNull()
                    ?.takeIf(Float::isFinite)
                    ?.coerceIn(0f, 1f) ?: 0f
            var dragValue by remember(node.id) { mutableStateOf<Float?>(null) }
            Slider(
                value = dragValue ?: value,
                onValueChange = { dragValue = it },
                modifier = modifier,
                enabled = node.props["enabled"] != "false",
                onValueChangeFinished = {
                    dragValue?.let { action(it.toString()) }
                    dragValue = null
                },
            )
        }

        "Progress" -> {
            val fraction =
                node.props["value"]
                    ?.toFloatOrNull()
                    ?.takeIf(Float::isFinite)
                    ?.coerceIn(0f, 1f) ?: 0f
            LinearProgressIndicator(
                progress = { fraction },
                modifier = modifier,
                color =
                    node.props["color"]?.let(::pluginColor) ?: MaterialTheme.colorScheme.primary,
                trackColor =
                    node.props["trackColor"]?.let(::pluginColor) ?: MaterialTheme.colorScheme.surfaceVariant,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }

        "Column", "List", "Section" -> {
            Column(
                modifier,
                verticalArrangement = Arrangement.spacedBy(spacing),
                horizontalAlignment =
                    when (node.props["horizontalAlignment"]) {
                        "center" -> Alignment.CenterHorizontally
                        "end" -> Alignment.End
                        else -> Alignment.Start
                    },
            ) {
                node.children.forEach { child ->
                    val weight = child.layoutWeight()
                    key(child.id) {
                        PluginNode(
                            child,
                            owner,
                            runtime,
                            target,
                            track,
                            if (weight ==
                                null
                            ) {
                                Modifier
                            } else {
                                Modifier.weight(weight)
                            },
                        )
                    }
                }
            }
        }

        "Row" -> {
            Row(
                modifier,
                horizontalArrangement =
                    when (node.props["horizontalArrangement"]) {
                        "center" -> Arrangement.spacedBy(spacing, Alignment.CenterHorizontally)
                        "end" -> Arrangement.spacedBy(spacing, Alignment.End)
                        "spaceBetween" -> Arrangement.SpaceBetween
                        else -> Arrangement.spacedBy(spacing)
                    },
                verticalAlignment =
                    when (node.props["verticalAlignment"]) {
                        "top" -> Alignment.Top
                        "bottom" -> Alignment.Bottom
                        else -> Alignment.CenterVertically
                    },
            ) {
                node.children.forEach { child ->
                    val weight = child.layoutWeight()
                    key(child.id) {
                        PluginNode(
                            child,
                            owner,
                            runtime,
                            target,
                            track,
                            if (weight ==
                                null
                            ) {
                                Modifier
                            } else {
                                Modifier.weight(weight)
                            },
                        )
                    }
                }
            }
        }

        "Button" -> {
            Button(
                onClick = { action(node.props["value"].orEmpty()) },
                modifier = modifier,
                enabled =
                    node.props["enabled"] != "false",
            ) {
                Text(text("text").ifEmpty { text("label") })
            }
        }

        "TextButton" -> {
            TextButton(
                onClick = { action(node.props["value"].orEmpty()) },
                modifier = modifier,
                enabled =
                    node.props["enabled"] != "false",
            ) {
                Text(
                    text("text").ifEmpty { text("label") },
                    color = node.props["color"]?.let(::pluginColor) ?: Color.Unspecified,
                    fontSize =
                        node.props["fontSize"]
                            ?.toIntOrNull()
                            ?.coerceIn(8, 96)
                            ?.sp ?: TextUnit.Unspecified,
                    fontWeight =
                        node.props["fontWeight"]
                            ?.toIntOrNull()
                            ?.coerceIn(100, 900)
                            ?.let(::FontWeight),
                    maxLines = node.props["maxLines"]?.toIntOrNull()?.coerceIn(1, 20) ?: Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        "Dialog" -> {
            var open by remember { mutableStateOf(false) }
            Button(onClick = { open = true }) { Text(text("label")) }
            if (open) {
                AlertDialog(
                    onDismissRequest = { open = false },
                    title = { Text(text("title")) },
                    text = {
                        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            node.children.forEach { key(it.id) { PluginNode(it, owner, runtime, target, track) } }
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
                Switch(checked, {
                    checked = it
                    action(it.toString())
                })
            }
        }

        "TextField", "Input" -> {
            var value by remember(node.id, node.props["value"]) { mutableStateOf(node.props["value"].orEmpty()) }
            if (node.props["appearance"] == "search") {
                val container = node.props["background"]?.let(::pluginColor) ?: MaterialTheme.colorScheme.surfaceVariant
                Row(
                    modifier.clip(CircleShape).background(container).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(pluginUiIcon("search"), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    BasicTextField(
                        value,
                        {
                            value = it.take(250)
                        },
                        Modifier.weight(
                            1f,
                        ),
                        singleLine = true,
                        textStyle =
                            MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                        cursorBrush =
                            SolidColor(
                                MaterialTheme.colorScheme.onSurface,
                            ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions =
                            KeyboardActions(onSearch = {
                                action(value)
                            }),
                        decorationBox = { input ->
                            Box {
                                if (value.isEmpty()) Text(text("label"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                input()
                            }
                        },
                    )
                }
            } else {
                Row(modifier, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value,
                        { value = it.take(250) },
                        Modifier.weight(1f),
                        label = { Text(text("label")) },
                        singleLine = true,
                    )
                    TextButton(onClick = { action(value) }) {
                        Text(text("submit").ifBlank { stringResource(Res.string.plugins_apply) })
                    }
                }
            }
        }

        else -> {
            val component = runtime.registry.component(node.type)
            if (component !=
                null
            ) {
                component.Content(node, UiContext(runtime.player, runtime.metadata, runtime.host.navigation::navigate, target = target))
            } else {
                Text(stringResource(Res.string.plugins_unsupported_ui, node.type))
            }
        }
    }
}

private fun pluginColor(value: String): Color? {
    if (!value.startsWith('#') || value.length !in setOf(7, 9)) return null
    val color = value.substring(1).toLongOrNull(16) ?: return null
    return Color(if (value.length == 7) 0xff000000 or color else color)
}

private fun UiNode.layoutWeight(): Float? = props["weight"]?.toFloatOrNull()?.takeIf { it.isFinite() && it in 0.1f..10f }

@Composable
private fun PluginArtwork(
    url: String,
    description: String?,
    modifier: Modifier,
) {
    val source = remember(url) { pluginArtworkSource(url) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (source != null) AsyncImage(source, description, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}
