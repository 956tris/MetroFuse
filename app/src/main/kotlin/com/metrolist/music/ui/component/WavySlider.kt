/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.WavyProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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

    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(normalizedValue) }

    val displayValue = if (isDragging) dragValue else normalizedValue

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

    val baseModifier = modifier
        .fillMaxWidth()
        .height(containerHeight)

    val interactiveModifier = if (enabled) {
        // A single pointerInput block handles both tap-to-seek and
        // drag-to-seek. Two separate pointerInput blocks (one with
        // detectTapGestures, one with detectHorizontalDragGestures) race:
        // the tap detector consumes the initial pointer-down first, which
        // makes the drag detector's touch-slop check see an
        // already-consumed down event and bail out immediately. The result
        // was that dragging never visually registered (isDragging never
        // became true) and, on release, the tap handler fired instead using
        // the ORIGINAL down position - i.e. the seek instantly "snapped
        // back" to near where the gesture started instead of landing where
        // the user dragged to.
        baseModifier
            // Stable Float keys, NOT the range object: Kotlin ranges have no
            // structural equality, so passing valueRange itself restarts this
            // detector on every recomposition (the player ticks 10x/sec) and
            // swallows all gestures mid-touch. Primitives compare by value.
            .pointerInput(valueRange.start, valueRange.endInclusive) {
                awaitEachGesture {
                    // requireUnconsumed = false: start tracking even when an
                    // ancestor already consumed the down event (e.g.
                    // bottom-sheet press handling). A seekbar must respond to
                    // touches on itself — otherwise the whole gesture is
                    // silently dropped and seeking breaks with no feedback.
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    isDragging = true
                    var finished = false
                    try {
                        dragValue = (down.position.x / size.width).coerceIn(0f, 1f)
                        onValueChange(
                            valueRange.start + dragValue * (valueRange.endInclusive - valueRange.start)
                        )

                        var pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId }
                                ?: event.changes.firstOrNull()
                                ?: break
                            pointerId = change.id

                            if (!change.pressed) {
                                // Pointer released or gesture cancelled. A
                                // cancelled drag must still finish: otherwise
                                // the pending seek value sticks forever and the
                                // position/buffer UI freezes at the touch point.
                                change.consume()
                                finished = true
                                onValueChangeFinished?.invoke()
                                break
                            }

                            change.consume()
                            dragValue = (change.position.x / size.width).coerceIn(0f, 1f)
                            onValueChange(
                                valueRange.start + dragValue * (valueRange.endInclusive - valueRange.start)
                            )
                        }
                    } finally {
                        // The detector is cooperatively cancelled when its key
                        // changes (e.g. duration loads mid-gesture) or the
                        // composable leaves: without this the thumb freezes
                        // and the pending seek is never committed.
                        isDragging = false
                        if (!finished) {
                            onValueChangeFinished?.invoke()
                        }
                    }
                }
            }
    } else {
        baseModifier
    }

    Box(
        modifier = interactiveModifier,
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
    }
}
