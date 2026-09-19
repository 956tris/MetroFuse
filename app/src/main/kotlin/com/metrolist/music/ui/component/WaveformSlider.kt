/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * SoundCloud-style bar waveform seek slider. Renders a fixed set of amplitude bars (from
 * SoundCloudAudioProvider.getWaveform) with the played portion drawn solid and the rest
 * translucent, matching SoundCloud's own player look. Falls back to the plain default track
 * (PlayerSliderTrack) when [samples] is null — e.g. no SoundCloud match was found for this
 * track, or the waveform hasn't loaded yet — so this is always safe to render immediately
 * rather than waiting on network.
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun WaveformSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    samples: List<Float>?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: SliderColors = SliderDefaults.colors(),
    bufferedValue: Float? = null,
    trackHeight: androidx.compose.ui.unit.Dp = 40.dp,
) {
    if (samples.isNullOrEmpty()) {
        // No SoundCloud match / not loaded yet — plain fallback, never block on network.
        Box(modifier = modifier.fillMaxWidth().height(trackHeight), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange,
                enabled = enabled,
                colors = colors,
                track = { sliderState ->
                    PlayerSliderTrack(
                        sliderState = sliderState,
                        colors = colors,
                        bufferedValue = bufferedValue,
                    )
                },
            )
        }
        return
    }

    val activeColor = colors.activeTrackColor
    val inactiveColor = colors.inactiveTrackColor
    val bufferedColor = activeColor.copy(alpha = 0.46f)

    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(value) }

    val currentValue = if (isDragging) dragPosition else value
    val duration = valueRange.endInclusive - valueRange.start
    val position = currentValue - valueRange.start

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight)
            .then(
                if (enabled) {
                    Modifier
                        .pointerInput(valueRange) {
                            detectTapGestures { offset ->
                                val newPosition = (offset.x / size.width) * duration
                                val mappedValue = valueRange.start + newPosition.coerceIn(0f, duration)
                                onValueChange(mappedValue)
                                onValueChangeFinished?.invoke()
                            }
                        }
                        .pointerInput(valueRange) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    isDragging = true
                                    val newPosition = (offset.x / size.width) * duration
                                    dragPosition = valueRange.start + newPosition.coerceIn(0f, duration)
                                    onValueChange(dragPosition)
                                },
                                onDragEnd = {
                                    isDragging = false
                                    onValueChangeFinished?.invoke()
                                },
                                onDragCancel = {
                                    isDragging = false
                                    // A cancelled drag must still finish: otherwise the
                                    // pending seek value sticks forever and the
                                    // position/buffer UI freezes at the touch point.
                                    onValueChangeFinished?.invoke()
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val newPosition = (change.position.x / size.width) * duration
                                    dragPosition = valueRange.start + newPosition.coerceIn(0f, duration)
                                    onValueChange(dragPosition)
                                },
                            )
                        }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight),
        ) {
            val progress = if (duration > 0f) (position / duration).coerceIn(0f, 1f) else 0f
            val bufferedProgress =
                bufferedValue
                    ?.let { if (duration > 0f) ((it - valueRange.start) / duration).coerceIn(progress, 1f) else progress }
                    ?: progress

            val barCount = samples.size
            if (barCount == 0) return@Canvas

            val totalWidth = size.width
            val barSlotWidth = totalWidth / barCount
            // M3 Expressive chunky-bar look: wider pill segments with a visible gap between
            // them, rather than thin SoundCloud-style hairlines. Explicit rounded rects (not
            // stroke-cap lines) so the corner radius reads clearly even on short/quiet bars.
            val barWidth = max(barSlotWidth * 0.72f, 3.dp.toPx())
            val cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f)
            val centerY = size.height / 2f
            val maxBarHeight = size.height * 0.88f
            // Quiet passages still render as a small rounded pill/dot rather than nearly
            // vanishing, matching M3 Expressive's preference for legible, chunky shapes.
            val minBarHeight = max(size.height * 0.16f, barWidth)

            val progressBarIndex = (progress * barCount).roundToInt().coerceIn(0, barCount)
            val bufferedBarIndex = (bufferedProgress * barCount).roundToInt().coerceIn(0, barCount)

            for (i in 0 until barCount) {
                val amplitude = samples[i].coerceIn(0f, 1f)
                val barHeight = minBarHeight + (maxBarHeight - minBarHeight) * amplitude
                val x = barSlotWidth * i + barSlotWidth / 2f

                val color = when {
                    i < progressBarIndex -> activeColor
                    i < bufferedBarIndex -> bufferedColor
                    else -> inactiveColor
                }

                drawRoundRect(
                    color = color,
                    topLeft = androidx.compose.ui.geometry.Offset(x - barWidth / 2f, centerY - barHeight / 2f),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                    cornerRadius = cornerRadius,
                )
            }
        }
    }
}
