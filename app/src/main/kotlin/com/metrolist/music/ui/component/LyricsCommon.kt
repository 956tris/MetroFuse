/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.metrolist.music.lyrics.LyricsEntry

sealed class LyricsListItem {
    data class Line(val index: Int, val entry: LyricsEntry) : LyricsListItem()
    data class Indicator(
        val afterLineIndex: Int,
        val gapMs: Long,
        val gapStartMs: Long,
        val gapEndMs: Long,
        val nextAgent: String?
    ) : LyricsListItem()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun IntervalIndicator(
    gapStartMs: Long,
    gapEndMs: Long,
    currentPositionMs: Long,
    visible: Boolean,
    color: Color,
    modifier: Modifier = Modifier
) {
    val alpha = remember { Animatable(0f) }
    val rowHeightPx = remember { Animatable(0f) }

    LaunchedEffect(visible) {
        if (visible) {
            rowHeightPx.animateTo(1f, tween(200))
            alpha.animateTo(1f, tween(200))
        } else {
            alpha.animateTo(0f, tween(200))
            rowHeightPx.animateTo(0f, tween(200))
        }
    }

    val density = LocalDensity.current
    val targetHeightDp = 72.dp

    val progress = if (gapEndMs > gapStartMs) {
        ((currentPositionMs - gapStartMs).toFloat() / (gapEndMs - gapStartMs).toFloat()).coerceIn(0f, 1f)
    } else 0f

    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 100, easing = LinearEasing),
        label = "intervalProgress"
    )

    Box(
        modifier = modifier
            .height(targetHeightDp * rowHeightPx.value)
            .padding(top = 16.dp * rowHeightPx.value)
            .graphicsLayer {
                this.alpha = alpha.value
                this.clip = true
            },
        contentAlignment = Alignment.Center
    ) {
        CircularWavyProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .size(36.dp)
                .alpha(alpha.value),
            color = color,
            trackColor = color.copy(alpha = 0.2f),
        )
    }
}

@Composable
internal fun SpicyIntervalDots(
    gapStartMs: Long,
    gapEndMs: Long,
    currentPositionMs: Long,
    visible: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val alpha = remember { Animatable(0f) }
    val rowHeightPx = remember { Animatable(0f) }

    LaunchedEffect(visible) {
        if (visible) {
            rowHeightPx.animateTo(1f, tween(180))
            alpha.animateTo(1f, tween(180))
        } else {
            alpha.animateTo(0f, tween(180))
            rowHeightPx.animateTo(0f, tween(180))
        }
    }

    val progress = if (gapEndMs > gapStartMs) {
        ((currentPositionMs - gapStartMs).toFloat() / (gapEndMs - gapStartMs).toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(
        modifier = modifier
            .height(72.dp * rowHeightPx.value)
            .padding(top = 18.dp * rowHeightPx.value)
            .graphicsLayer {
                this.alpha = alpha.value
                this.clip = true
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.width(58.dp),
        ) {
            repeat(3) { index ->
                val dotProgress = ((progress - index * 0.075f) / 0.42f).coerceIn(0f, 1f)
                val dotScale = spicyDotsSpline(
                    dotProgress,
                    listOf(
                        SpicyDotPoint(0f, 0.75f),
                        SpicyDotPoint(0.7f, 1.05f),
                        SpicyDotPoint(1f, 1f),
                    ),
                )
                val dotYOffset = spicyDotsSpline(
                    dotProgress,
                    listOf(
                        SpicyDotPoint(0f, 0f),
                        SpicyDotPoint(0.9f, -0.12f),
                        SpicyDotPoint(1f, 0f),
                    ),
                )
                val dotAlpha = spicyDotsSpline(
                    dotProgress,
                    listOf(
                        SpicyDotPoint(0f, 0.35f),
                        SpicyDotPoint(0.6f, 1f),
                        SpicyDotPoint(1f, 1f),
                    ),
                )

                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .graphicsLayer {
                            scaleX = dotScale
                            scaleY = dotScale
                            translationY = dotYOffset * 28.dp.toPx()
                            this.alpha = dotAlpha
                        }
                        .background(color.copy(alpha = dotAlpha), CircleShape),
                )
            }
        }
    }
}

private data class SpicyDotPoint(val time: Float, val value: Float)

private fun spicyDotsSpline(progress: Float, points: List<SpicyDotPoint>): Float {
    val clamped = progress.coerceIn(0f, 1f)
    val nextIndex = points.indexOfFirst { it.time >= clamped }.takeIf { it >= 0 } ?: points.lastIndex
    if (nextIndex == 0) return points.first().value
    val previous = points[nextIndex - 1]
    val next = points[nextIndex]
    val span = (next.time - previous.time).coerceAtLeast(0.0001f)
    val local = ((clamped - previous.time) / span).coerceIn(0f, 1f)
    val eased = local * local * (3f - 2f * local)
    return previous.value + (next.value - previous.value) * eased
}
