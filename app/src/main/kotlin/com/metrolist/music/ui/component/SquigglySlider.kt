/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 * 
 * Squiggly Slider - ported from mpvEx project
 * https://github.com/marlboro-advance/mpvEx
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import com.metrolist.music.ui.theme.PlayerSliderColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun SquigglySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
    isPlaying: Boolean = true,
    bufferedValue: Float? = null,
) {
    val primaryColor = colors.activeTrackColor
    val inactiveColor = colors.inactiveTrackColor

    // Interaction is delegated to a real M3 Slider rendered invisibly on top:
    // same tap-to-seek and drag-to-seek engine as the working Default/Slim
    // styles, with the squiggly visuals purely presentational underneath. The
    // previous hand-rolled pointerInput detector silently dropped gestures.
    val currentValue = value
    val duration = valueRange.endInclusive - valueRange.start
    val position = currentValue - valueRange.start

    // Animation state
    var phaseOffset by remember { mutableFloatStateOf(0f) }
    var heightFraction by remember { mutableFloatStateOf(if (isPlaying) 1f else 0f) }

    val scope = rememberCoroutineScope()

    // Wave parameters
    val waveLength = 80f
    val lineAmplitude = 6f
    val phaseSpeed = 24f // Faster wave movement to match old squiggly
    val transitionPeriods = 1.5f
    val minWaveEndpoint = 0f
    val matchedWaveEndpoint = 1f
    val transitionEnabled = true

    // Animate height fraction based on playing state.
    LaunchedEffect(isPlaying) {
        scope.launch {
            val shouldFlatten = !isPlaying
            val targetHeight = if (shouldFlatten) 0f else 1f
            val animDuration = if (shouldFlatten) 150 else 200 // Faster appear/disappear
            val startDelay = if (shouldFlatten) 0L else 30L

            delay(startDelay)

            val animator = Animatable(heightFraction)
            animator.animateTo(
                targetValue = targetHeight,
                animationSpec = tween(
                    durationMillis = animDuration,
                    easing = LinearEasing,
                ),
            ) {
                heightFraction = this.value
            }
        }
    }

    // Animate wave movement only when playing
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect

        var lastFrameTime = withFrameMillis { it }
        while (isActive) {
            withFrameMillis { frameTimeMillis ->
                val deltaTime = (frameTimeMillis - lastFrameTime) / 1000f
                phaseOffset += deltaTime * phaseSpeed
                phaseOffset %= waveLength
                lastFrameTime = frameTimeMillis
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            val strokeWidth = 5.dp.toPx()
            val progress = if (duration > 0f) (position / duration).coerceIn(0f, 1f) else 0f
            val bufferedProgress =
                bufferedValue
                    ?.let { if (duration > 0f) ((it - valueRange.start) / duration).coerceIn(progress, 1f) else progress }
                    ?: progress
            val totalWidth = size.width
            val totalProgressPx = totalWidth * progress
            val totalBufferedPx = totalWidth * bufferedProgress
            val centerY = size.height / 2f

            // Calculate wave progress
            val waveProgressPx = if (!transitionEnabled || progress > matchedWaveEndpoint) {
                totalWidth * progress
            } else {
                val t = (progress / matchedWaveEndpoint).coerceIn(0f, 1f)
                totalWidth * (minWaveEndpoint + (matchedWaveEndpoint - minWaveEndpoint) * t)
            }

            // Helper function to compute amplitude
            fun computeAmplitude(x: Float, sign: Float): Float {
                return if (transitionEnabled) {
                    val length = transitionPeriods * waveLength
                    val coeff = ((waveProgressPx + length / 2f - x) / length).coerceIn(0f, 1f)
                    sign * heightFraction * lineAmplitude * coeff
                } else {
                    sign * heightFraction * lineAmplitude
                }
            }

            // Build wavy path for played portion
            val path = Path()
            val waveStart = -phaseOffset - waveLength / 2f
            val waveEnd = if (transitionEnabled) totalWidth else waveProgressPx

            path.moveTo(waveStart, centerY)

            var currentX = waveStart
            var waveSign = 1f
            var currentAmp = computeAmplitude(currentX, waveSign)
            val dist = waveLength / 2f

            while (currentX < waveEnd) {
                waveSign = -waveSign
                val nextX = currentX + dist
                val midX = currentX + dist / 2f
                val nextAmp = computeAmplitude(nextX, waveSign)

                path.cubicTo(
                    midX,
                    centerY + currentAmp,
                    midX,
                    centerY + nextAmp,
                    nextX,
                    centerY + nextAmp,
                )

                currentAmp = nextAmp
                currentX = nextX
            }

            // Draw path up to progress position using clipping
            val clipTop = lineAmplitude + strokeWidth

            val disabledAlpha = 77f / 255f
            val inactiveTrackColor = primaryColor.copy(alpha = disabledAlpha)
            val bufferedTrackColor = PlayerSliderColors.bufferedTrackColor(primaryColor)
            val capRadius = strokeWidth / 2f

            fun drawPathSegment(startX: Float, endX: Float, color: Color) {
                if (endX <= startX) return
                clipRect(
                    left = startX,
                    top = centerY - clipTop,
                    right = endX,
                    bottom = centerY + clipTop,
                ) {
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
            }

            drawPathSegment(0f, totalWidth, inactiveTrackColor)
            drawPathSegment(0f, totalBufferedPx, bufferedTrackColor)
            drawPathSegment(0f, totalProgressPx, primaryColor)

            // Helper function to get wave Y position at any X
            fun getWaveY(x: Float): Float {
                val phase = (x - waveStart) / waveLength
                val waveCycle = phase - kotlin.math.floor(phase)
                val waveValue = kotlin.math.cos(waveCycle * 2f * kotlin.math.PI.toFloat())
                
                // Calculate amplitude coefficient at this x position
                val ampCoeff = if (transitionEnabled) {
                    val length = transitionPeriods * waveLength
                    ((waveProgressPx + length / 2f - x) / length).coerceIn(0f, 1f)
                } else {
                    1f
                }
                
                return centerY + waveValue * lineAmplitude * heightFraction * ampCoeff
            }

            // Draw round cap at start (synced with wave)
            drawCircle(
                color = primaryColor,
                radius = capRadius,
                center = Offset(0f, getWaveY(0f)),
            )

            // Draw round cap at end (only right half, synced with wave movement)
            val endWaveY = getWaveY(totalWidth)
            clipRect(
                left = totalWidth,
                top = centerY - clipTop,
                right = totalWidth + capRadius,
                bottom = centerY + clipTop,
            ) {
                drawCircle(
                    color = inactiveTrackColor,
                    radius = capRadius,
                    center = Offset(totalWidth, endWaveY),
                )
            }

            // Vertical Bar Thumb
            val barHalfHeight = (lineAmplitude + strokeWidth)
            val barWidth = 5.dp.toPx()

            if (barHalfHeight > 0.5f) {
                drawLine(
                    color = primaryColor,
                    start = Offset(totalProgressPx, centerY - barHalfHeight),
                    end = Offset(totalProgressPx, centerY + barHalfHeight),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round,
                )
            }
        }

        // Invisible M3 Slider owns all interaction (same engine as Default).
        // Empty track/thumb: all visuals come from the canvas above, driven
        // by the value prop which the parent updates on every onValueChange.
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            enabled = enabled,
            track = {},
            thumb = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}
