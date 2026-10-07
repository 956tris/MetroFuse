/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.music.ui.theme.PlayerSliderColors

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
    val stroke = remember(strokeWidthPx) {
        Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
    }

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

    // Interaction is delegated to a real M3 Slider rendered invisibly on top:
    // same tap-to-seek and drag-to-seek engine (nested-scroll aware, slop
    // handling, press indication) as the working Default/Slim styles, with
    // the wavy visuals purely presentational underneath. The previous
    // hand-rolled pointerInput detector silently dropped every gesture here.
    val displayValue = normalizedValue

    val animatedAmplitude by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
        label = "amplitude"
    )

    val activeColor = colors.activeTrackColor
    val inactiveColor = colors.inactiveTrackColor
    val bufferedColor = PlayerSliderColors.bufferedTrackColor(activeColor)
    val thumbColor = colors.thumbColor

    // Calculate container height to accommodate thumb
    val containerHeight = maxOf(WavyProgressIndicatorDefaults.LinearContainerHeight, thumbRadius * 2)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(containerHeight),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val y = size.height / 2f
            // Straight tracks are drawn ahead of the playhead only. The
            // active track is a wave oscillating around center, so any
            // straight line behind it peeks through as an ugly line.
            val progressX = size.width * displayValue
            val bufferedEndX = size.width * normalizedBufferedValue
            if (bufferedEndX > progressX + 0.5f) {
                drawLine(
                    color = bufferedColor,
                    start = Offset(progressX, y),
                    end = Offset(bufferedEndX, y),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round,
                )
            }
            val inactiveStartX = maxOf(progressX, bufferedEndX)
            if (size.width > inactiveStartX + 0.5f) {
                drawLine(
                    color = inactiveColor,
                    start = Offset(inactiveStartX, y),
                    end = Offset(size.width, y),
                    strokeWidth = strokeWidthPx,
                    cap = StrokeCap.Round,
                )
            }
        }

        LinearWavyProgressIndicator(
            progress = { displayValue },
            modifier = Modifier.fillMaxWidth(),
            color = activeColor,
            trackColor = Color.Transparent,
            stroke = stroke,
            trackStroke = stroke,
            gapSize = thumbRadius + 4.dp,
            // No stop dot at the track end.
            stopSize = 0.dp,
            amplitude = { progress -> if (progress > 0f) animatedAmplitude else 0f },
            wavelength = wavelength,
            waveSpeed = waveSpeed
        )

        // Draw circular thumb - synced with progress indicator position
        Canvas(modifier = Modifier.fillMaxSize()) {
            val thumbX = size.width * displayValue
            val thumbY = size.height / 2

            drawCircle(
                color = thumbColor,
                radius = thumbRadiusPx,
                center = Offset(thumbX, thumbY)
            )
        }

        // Invisible M3 Slider owns all interaction (same engine as Default).
        // Empty track/thumb: all visuals come from the canvases above, driven
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
