/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 * Ported from MetroFuse d835f2ef.
 */

package com.metrolist.shared.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import com.metrolist.shared.ui.components.LocalVisualWorkActive
import com.metrolist.shared.ui.theme.rememberReducedMotion
import kotlin.math.floor
import kotlin.math.roundToInt

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
internal fun GalaxyStarOverlay(
    modifier: Modifier = Modifier,
    skyColors: List<Color> = emptyList(),
    animated: Boolean = true,
    // Lets a blurred-artwork base glow through underneath (Galaxy blur).
    // Full opacity keeps the classic solid Galaxy look.
    skyAlpha: Float = 1f,
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
    val visible by LocalVisualWorkActive.current.collectAsState()
    val motionEnabled = visible && !rememberReducedMotion()
    val animateStars = animated && motionEnabled
    val colorTransition = remember(motionEnabled) { if (motionEnabled) tween<Color>(900) else snap() }
    val topColor by animateColorAsState(
        targetValue = (skyColors.getOrNull(0) ?: Color.Black).copy(alpha = skyAlpha),
        animationSpec = colorTransition,
        label = "galaxyTopColor",
    )
    val midColor by animateColorAsState(
        targetValue = (skyColors.getOrNull(1) ?: Color(0xFF020202)).copy(alpha = skyAlpha),
        animationSpec = colorTransition,
        label = "galaxyMidColor",
    )
    val bottomColor by animateColorAsState(
        targetValue = (skyColors.getOrNull(2) ?: Color.Black).copy(alpha = skyAlpha),
        animationSpec = colorTransition,
        label = "galaxyBottomColor",
    )
    val glowColor by animateColorAsState(
        targetValue = skyColors.getOrNull(3) ?: Color.White,
        animationSpec = colorTransition,
        label = "galaxyGlowColor",
    )

    LaunchedEffect(animateStars) {
        if (!animateStars) return@LaunchedEffect
        var lastFrame = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            val delta = now - lastFrame
            if (delta >= FrameIntervalMs || delta < 0L) {
                frameMillis += delta.coerceIn(0L, 100L)
                lastFrame = now
            }
        }
    }

    Box(modifier) {
        // Record the gradient and all steady star cores separately from the animated layer.
        Box(
            Modifier.fillMaxSize().graphicsLayer().drawWithCache {
                val sky = Brush.verticalGradient(0f to topColor, 0.58f to midColor, 1f to bottomColor)
                val starCount = (size.width * size.height / 9_500f).roundToInt().coerceIn(16, stars.size)
                val centers = List(starCount) { index ->
                    Offset(size.width * stars[index].x, size.height * stars[index].y)
                }
                onDrawBehind {
                    drawRect(sky)
                    centers.forEachIndexed { index, center ->
                        val star = stars[index]
                        drawCircle(Color.White.copy(alpha = star.opacity), star.sizePx / 2f, center)
                    }
                }
            },
        )

        Box(
            Modifier.fillMaxSize().graphicsLayer().drawWithCache {
                val starCount = (size.width * size.height / 9_500f).roundToInt().coerceIn(16, stars.size)
                val twinkling = stars.take(starCount).filter { it.twinkles }.map { star ->
                    star to Offset(size.width * star.x, size.height * star.y)
                }
                onDrawBehind {
                    val timeSeconds = frameMillis / 1000f
                    val glow = glowColor
                    for ((star, center) in twinkling) {
                        val coreRadius = star.sizePx / 2f
                        val glowAlpha = star.opacity * twinkleGlow(star.twinklePattern, timeSeconds)
                        if (glowAlpha > 0.02f) {
                            drawCircle(glow.copy(alpha = glowAlpha * 0.08f), coreRadius + 6f, center)
                            drawCircle(glow.copy(alpha = glowAlpha * 0.14f), coreRadius + 3f, center)
                        }
                    }

                    if (animateStars) {
                        shootingStars.forEachIndexed { index, star ->
                            val shiftedTime = timeSeconds + star.delaySeconds
                            val cycle = floor(shiftedTime / star.cycleSeconds).toInt()
                            drawShootingStar(
                                fromTop = seededUnit(index + cycle * 17, 41, 5) < 0.75f,
                                edgePosition = seededUnit(index + cycle * 29, 73, 31) * 0.9f,
                                crossPosition = seededUnit(index + cycle * 37, 97, 43) * 0.5f,
                                progress = (shiftedTime % star.cycleSeconds) / star.cycleSeconds,
                                glowColor = glow,
                            )
                        }
                    }
                }
            },
        )
    }
}

private fun DrawScope.drawShootingStar(
    fromTop: Boolean,
    edgePosition: Float,
    crossPosition: Float,
    progress: Float,
    glowColor: Color,
) {
    val visibleProgress = progress.coerceIn(0f, 1f)
    val fade =
        if (visibleProgress < 0.7f) {
            1f
        } else {
            (1f - ((visibleProgress - 0.7f) / 0.3f)).coerceIn(0f, 1f)
        }
    if (fade <= 0.01f) return

    val start =
        if (fromTop) {
            Offset(size.width * edgePosition, -4f)
        } else {
            Offset(size.width + 4f, size.height * crossPosition)
        }
    val direction = Offset(-0.70710677f, 0.70710677f)
    val travel = size.maxDimension * 1.5f
    val head = Offset(
        x = start.x + direction.x * travel * visibleProgress,
        y = start.y + direction.y * travel * visibleProgress,
    )
    val tailLength = size.minDimension.coerceAtLeast(320f) * 0.62f
    // The tail trails up and right. Skip draws when the whole meteor is outside the viewport.
    if (head.x > size.width + 10f || head.y < -10f || head.x < -tailLength - 10f || head.y > size.height + tailLength + 10f) return
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
