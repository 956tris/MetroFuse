/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val StarPoolSize = 260
private const val ShootingStarCount = 2
private const val TwinkleCycleSeconds = 6.5f
private const val FrameIntervalMs = 33L

private data class StarryNightStar(
    val x: Float,
    val y: Float,
    val sizePx: Float,
    val opacity: Float,
    val twinklePattern: Int,
    val twinkles: Boolean,
)

private data class ShootingStarBase(
    val cycleSeconds: Float,
    val delaySeconds: Float,
)

@Composable
fun GalaxyStarOverlay(
    modifier: Modifier = Modifier,
    intensity: Float = 1f,
    skyColors: List<Color> = emptyList(),
    animated: Boolean = true,
) {
    val stars = remember {
        List(StarPoolSize) { index ->
            StarryNightStar(
                x = seededUnit(index, 29, 7),
                y = seededUnit(index, 47, 13),
                sizePx = if (seededUnit(index, 17, 3) < 0.58f) 1f else 2f,
                opacity = 0.42f + seededUnit(index, 71, 19) * 0.48f,
                twinklePattern = (index * 11 + 5) % 4,
                twinkles = (index * 23 + 3) % 5 == 0,
            )
        }
    }
    val shootingStars = remember {
        List(ShootingStarCount) { index ->
            ShootingStarBase(
                cycleSeconds = 5.4f + seededUnit(index, 31, 11) * 2.8f,
                delaySeconds = seededUnit(index, 67, 23) * 9f,
            )
        }
    }
    var frameMillis by remember { mutableLongStateOf(0L) }
    val topColor by animateColorAsState(
        targetValue = skyColors.getOrNull(0) ?: Color.Black,
        animationSpec = tween(900),
        label = "galaxyTopColor",
    )
    val midColor by animateColorAsState(
        targetValue = skyColors.getOrNull(1) ?: Color(0xFF020202),
        animationSpec = tween(900),
        label = "galaxyMidColor",
    )
    val bottomColor by animateColorAsState(
        targetValue = skyColors.getOrNull(2) ?: Color.Black,
        animationSpec = tween(900),
        label = "galaxyBottomColor",
    )
    val glowColor by animateColorAsState(
        targetValue = skyColors.getOrNull(3) ?: Color.White,
        animationSpec = tween(900),
        label = "galaxyGlowColor",
    )

    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        var lastDrawnFrame = 0L
        while (true) {
            val nextFrame = withFrameMillis { it }
            // If nextFrame is somehow 0 or less, or if the delta is huge/negative (which can happen on some devices),
            // just update it without the interval check to avoid potential state issues that could lead to NaN
            if (nextFrame - lastDrawnFrame >= FrameIntervalMs || nextFrame < lastDrawnFrame) {
                lastDrawnFrame = nextFrame
                frameMillis = nextFrame
            }
        }
    }

    Canvas(modifier = modifier) {
        val alphaScale = intensity.coerceIn(0f, 1f)
        val timeSeconds = frameMillis / 1000f

        drawRect(
            brush = Brush.verticalGradient(
                0f to topColor,
                0.58f to midColor,
                1f to bottomColor,
            ),
            size = size,
        )

        val starCount = ((size.width * size.height) / 9500f)
            .roundToInt()
            .coerceIn(16, stars.size)

        for (index in 0 until starCount) {
            val star = stars[index]
            val center = Offset(size.width * star.x, size.height * star.y)
            val coreRadius = star.sizePx / 2f
            val coreAlpha = star.opacity * alphaScale
            val glowAlpha =
                if (star.twinkles) {
                    coreAlpha * twinkleGlow(star.twinklePattern, timeSeconds)
                } else {
                    0f
                }

            if (glowAlpha > 0.02f) {
                drawCircle(
                    color = glowColor.copy(alpha = glowAlpha * 0.08f),
                    radius = coreRadius + 6f,
                    center = center,
                )
                drawCircle(
                    color = glowColor.copy(alpha = glowAlpha * 0.14f),
                    radius = coreRadius + 3f,
                    center = center,
                )
            }
            drawCircle(
                color = Color.White.copy(alpha = coreAlpha),
                radius = coreRadius,
                center = center,
            )
        }

        shootingStars.forEachIndexed { index, star ->
            val shiftedTime = timeSeconds + star.delaySeconds
            val cycle = floor(shiftedTime / star.cycleSeconds).toInt()
            val progress = (shiftedTime % star.cycleSeconds) / star.cycleSeconds
            val fromTop = seededUnit(index + cycle * 17, 41, 5) < 0.75f
            val edgePosition = seededUnit(index + cycle * 29, 73, 31) * 0.9f
            val crossPosition = seededUnit(index + cycle * 37, 97, 43) * 0.5f
            drawShootingStar(
                fromTop = fromTop,
                edgePosition = edgePosition,
                crossPosition = crossPosition,
                progress = progress,
                alphaScale = alphaScale,
                glowColor = glowColor,
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShootingStar(
    fromTop: Boolean,
    edgePosition: Float,
    crossPosition: Float,
    progress: Float,
    alphaScale: Float,
    glowColor: Color,
) {
    val visibleProgress = progress.coerceIn(0f, 1f)
    val fade =
        if (visibleProgress < 0.7f) {
            1f
        } else {
            (1f - ((visibleProgress - 0.7f) / 0.3f)).coerceIn(0f, 1f)
        } * alphaScale
    if (fade <= 0.01f) return

    val start =
        if (fromTop) {
            Offset(size.width * edgePosition, -4f)
        } else {
            Offset(size.width + 4f, size.height * crossPosition)
        }
    val direction = Offset(-1f / sqrt(2f), 1f / sqrt(2f))
    val travel = size.maxDimension * 1.5f
    val head = Offset(
        x = start.x + direction.x * travel * visibleProgress,
        y = start.y + direction.y * travel * visibleProgress,
    )
    val tailLength = size.minDimension.coerceAtLeast(320f) * 0.62f
    val segmentCount = 5

    repeat(segmentCount) { segment ->
        val startFactor = segment / segmentCount.toFloat()
        val endFactor = (segment + 1) / segmentCount.toFloat()
        val startPoint = Offset(
            x = head.x - direction.x * tailLength * startFactor,
            y = head.y - direction.y * tailLength * startFactor,
        )
        val endPoint = Offset(
            x = head.x - direction.x * tailLength * endFactor,
            y = head.y - direction.y * tailLength * endFactor,
        )
        val segmentAlpha = fade * (1f - startFactor).coerceIn(0f, 1f)
        drawLine(
            color = glowColor.copy(alpha = segmentAlpha * 0.10f),
            start = startPoint,
            end = endPoint,
            strokeWidth = 10f,
        )
        drawLine(
            color = glowColor.copy(alpha = segmentAlpha * 0.20f),
            start = startPoint,
            end = endPoint,
            strokeWidth = 2.5f,
        )
    }

    drawCircle(
        color = glowColor.copy(alpha = fade * 0.12f),
        radius = 10f,
        center = head,
    )
    drawCircle(
        color = glowColor.copy(alpha = fade * 0.24f),
        radius = 7f,
        center = head,
    )
    drawCircle(
        color = Color.White.copy(alpha = fade),
        radius = 2f,
        center = head,
    )
}

private fun seededUnit(
    index: Int,
    multiplier: Int,
    offset: Int,
): Float =
    (((index * multiplier + offset).floorMod(1000)) / 1000f)

private fun Int.floorMod(modulus: Int): Int =
    ((this % modulus) + modulus) % modulus

private fun twinkleGlow(
    pattern: Int,
    timeSeconds: Float,
): Float {
    val phase = (((timeSeconds / TwinkleCycleSeconds) + pattern * 0.21f) % 1f)
    val pulse = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
    return pulse * pulse
}
