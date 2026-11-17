package com.metrolist.shared.plugins

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private val pluginUiIconPaths =
    mapOf(
        "home" to "M12 3 2 11h3v10h5v-6h4v6h5V11h3Z",
        "search" to "M10 2a8 8 0 1 0 4.9 14.3L21 22l1-1-5.7-6.1A8 8 0 0 0 10 2Zm0 2a6 6 0 1 1 0 12 6 6 0 0 1 0-12Z",
        "library" to "M3 3h2v18H3Zm5 0h2v18H8Zm5 1 2-.5 5 17-2 .5Z",
        "play" to "M7 3v18l15-9Z",
        "pause" to "M6 4h4v16H6Zm8 0h4v16h-4Z",
        "next" to "M5 4v16l12-8ZM18 4h3v16h-3Z",
        "previous" to "M19 4v16L7 12ZM3 4h3v16H3Z",
        "profile" to "M12 2a5 5 0 1 0 0 10 5 5 0 0 0 0-10ZM3 22v-3c0-7 18-7 18 0v3Z",
        "down" to "m3 8 9 9 9-9-2-2-7 7-7-7Z",
        "back" to "m12 3 2 2-6 6h14v2H8l6 6-2 2-9-9Z",
        "queue" to "M3 4h18v2H3Zm0 6h18v2H3Zm0 6h12v2H3Zm14-2 6 4-6 4Z",
        "add" to "M11 3h2v8h8v2h-8v8h-2v-8H3v-2h8Z",
        "shuffle" to "M3 5h4l10 12h2v-3l5 4-5 4v-3h-3L6 7H3Zm0 12h4l3-4 2 2-4 4H3Zm10-8 3-4h3V2l5 4-5 4V7h-2l-3 4Z",
        "repeat" to "M5 4h13V1l5 4-5 4V6H5v5H3V6Zm14 16H6v3l-5-4 5-4v3h13v-5h2v5Z",
        "expand" to "M3 3h7v2H5v5H3Zm11 0h7v7h-2V5h-5ZM3 14h2v5h5v2H3Zm16 0h2v7h-7v-2h5Z",
        "settings" to
            "M10 2h4l.7 3 2 .9 2.8-.9 2 3.5-2.2 2v2.9l2.2 2-2 3.5-2.8-.9-2 .9-.7 3h-4l-.7-3-2-.9-2.8.9-2-3.5 2.2-2v-2.9l-2.2-2 2-3.5 2.8.9 2-.9Zm2 6a4 4 0 1 0 0 8 4 4 0 0 0 0-8Z",
        "more" to "M4 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4Zm8 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4Zm8 0a2 2 0 1 0 0 4 2 2 0 0 0 0-4Z",
    )

internal fun pluginUiIconName(name: String): String = name.takeIf(pluginUiIconPaths::containsKey) ?: "more"

@Composable
internal fun pluginUiIcon(name: String): ImageVector {
    val resolved = pluginUiIconName(name)
    return remember(resolved) {
        ImageVector
            .Builder(resolved, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(pluginUiIconPaths.getValue(resolved)).toNodes(), fill = SolidColor(Color.White))
            .build()
    }
}
