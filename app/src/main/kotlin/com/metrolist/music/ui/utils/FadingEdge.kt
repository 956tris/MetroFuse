/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.utils

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp

fun Modifier.fadingEdge(
    left: Dp? = null,
    top: Dp? = null,
    right: Dp? = null,
    bottom: Dp? = null,
) = then(
    if (left != null || top != null || right != null || bottom != null) {
        // Offscreen layer is only required for the DstIn blend; skip it when
        // no edge is active instead of paying for a layer that blends nothing.
        Modifier.graphicsLayer(alpha = 0.99f)
    } else {
        Modifier
    }
).drawWithCache {
    // Brushes cached per size: allocating gradients on every draw burns
    // scroll frames for identical pixels.
    val topBrush =
        top?.let {
            Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black),
                startY = 0f,
                endY = it.toPx(),
            )
        }
    val bottomBrush =
        bottom?.let {
            Brush.verticalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startY = size.height - it.toPx(),
                endY = size.height,
            )
        }
    val leftBrush =
        left?.let {
            Brush.horizontalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startX = 0f,
                endX = it.toPx(),
            )
        }
    val rightBrush =
        right?.let {
            Brush.horizontalGradient(
                colors = listOf(Color.Transparent, Color.Black),
                startX = size.width - it.toPx(),
                endX = size.width,
            )
        }
    onDrawWithContent {
        drawContent()
        topBrush?.let { drawRect(brush = it, blendMode = BlendMode.DstIn) }
        bottomBrush?.let { drawRect(brush = it, blendMode = BlendMode.DstIn) }
        leftBrush?.let { drawRect(brush = it, blendMode = BlendMode.DstIn) }
        rightBrush?.let { drawRect(brush = it, blendMode = BlendMode.DstIn) }
    }
}

fun Modifier.fadingEdge(
    horizontal: Dp? = null,
    vertical: Dp? = null,
) = fadingEdge(
    left = horizontal,
    right = horizontal,
    top = vertical,
    bottom = vertical,
)
