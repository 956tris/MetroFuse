/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Wavy slider with a self-drawn sine wave. Everything (played wave,
 * buffered segment, inactive track, thumb) lives in one canvas and one
 * coordinate space so the segments always line up: no stop dot, no gap
 * notch, no stray straight line peeking out from under the wave.
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.sin

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WavySlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
    isPlaying: Boolean = true,
    enabled: Boolean = true,
    strokeWidth: Dp = 4.dp,
    thumbRadius: Dp = 8.dp,
    wavelength: Dp = WavyProgressIndicatorDefaults.LinearDeterminateWavelength,
    waveSpeed: Dp = wavelength,
    bufferedValue: Float? = null,
) {
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { strokeWidth.toPx() }
    val thumbRadiusPx = with(density) { thumbRadius.toPx() }
    val wavelengthPx = with(density) { wavelength.toPx() }.coerceAtLeast(8f)
    val waveSpeedPxPerSec = with(density) { waveSpeed.toPx() }
    val amplitudePx = with(density) { 5.dp.toPx() }

    val duration = valueRange.endInclusive - valueRange.start
    val normalizedValue = if (duration > 0f) {
        ((value - valueRange.start) / duration).coerceIn(0f, 1f)
    } else {
        0f
    }
    val normalizedBufferedValue =
        bufferedValue
            ?.let { if (duration > 0f) ((it - valueRange.start) / duration).coerceIn(normalizedValue, 1f) else normalizedValue }
            ?: normalizedValue

    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(normalizedValue) }

    val displayValue = if (isDragging) dragValue else normalizedValue

    val animatedAmplitude by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "amplitude"
    )

    var phasePx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying, waveSpeedPxPerSec, wavelengthPx) {
        if (!isPlaying && waveSpeedPxPerSec == 0f) return@LaunchedEffect
        var lastFrameTime = withFrameMillis { it }
        while (isActive) {
            withFrameMillis { frameTimeMillis ->
                val deltaTime = (frameTimeMillis - lastFrameTime) / 1000f
                phasePx = (phasePx + deltaTime * waveSpeedPxPerSec) % wavelengthPx
                lastFrameTime = frameTimeMillis
            }
            if (!isPlaying) break
        }
    }

    val activeColor = colors.activeTrackColor
    val inactiveColor = colors.inactiveTrackColor
    val bufferedColor = activeColor.copy(alpha = 0.46f)
    val thumbColor = colors.thumbColor

    // Calculate container height to accommodate thumb
    val containerHeight = maxOf(WavyProgressIndicatorDefaults.LinearContainerHeight, thumbRadius * 2)

    val baseModifier = modifier
        .fillMaxWidth()
        .height(containerHeight)

    val interactiveModifier = if (enabled) {
        baseModifier
            .pointerInput(valueRange) {
                detectTapGestures { offset ->
                    val newValue = (offset.x / size.width).coerceIn(0f, 1f)
                    val mappedValue = valueRange.start + newValue * (valueRange.endInclusive - valueRange.start)
                    onValueChange(mappedValue)
                    onValueChangeFinished?.invoke()
                }
            }
            .pointerInput(valueRange) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragValue = (offset.x / size.width).coerceIn(0f, 1f)
                        val mappedValue = valueRange.start + dragValue * (valueRange.endInclusive - valueRange.start)
                        onValueChange(mappedValue)
                    },
                    onDragEnd = {
                        isDragging = false
                        onValueChangeFinished?.invoke()
                    },
                    onDragCancel = {
                        isDragging = false
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        dragValue = (dragValue + dragAmount / size.width).coerceIn(0f, 1f)
                        val mappedValue = valueRange.start + dragValue * (valueRange.endInclusive - valueRange.start)
                        onValueChange(mappedValue)
                    }
                )
            }
    } else {
        baseModifier
    }

    Box(
        modifier = interactiveModifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val totalWidth = size.width
            val centerY = size.height / 2f
            val progressX = totalWidth * displayValue
            val bufferedEndX = totalWidth * normalizedBufferedValue
            val amplitude = amplitudePx * animatedAmplitude

            // One shared sine-wave path across the whole width so every
            // segment below follows the exact same curve.
            val path = Path()
            val stepPx = 4f
            var x = -wavelengthPx
            path.moveTo(x, centerY + (sin((x + phasePx) / wavelengthPx * 2f * PI).toFloat() * amplitude))
            x += stepPx
            while (x <= totalWidth + wavelengthPx) {
                val y = centerY + (sin((x + phasePx) / wavelengthPx * 2f * PI).toFloat() * amplitude)
                path.lineTo(x, y)
                x += stepPx
            }

            val waveStroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            val clipTop = amplitudePx + strokeWidthPx

            fun drawWaveSegment(startX: Float, endX: Float, color: Color) {
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
                        style = waveStroke,
                    )
                }
            }

            // Inactive first (full width), then buffered ahead of the
            // playhead only, then played. Nothing straight is ever drawn
            // behind the wave, so nothing peeks out from under it.
            // The opaque thumb covers the joints.
            drawWaveSegment(0f, totalWidth, inactiveColor)
            if (bufferedEndX > progressX + 0.5f) {
                drawWaveSegment(progressX, bufferedEndX, bufferedColor)
            }
            drawWaveSegment(0f, progressX, activeColor)

            drawCircle(
                color = thumbColor,
                radius = thumbRadiusPx,
                center = Offset(progressX, centerY),
            )
        }
    }
}
