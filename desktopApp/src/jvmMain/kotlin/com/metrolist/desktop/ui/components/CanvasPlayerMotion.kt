package com.metrolist.desktop.ui.components

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.ui.navigation.blockInput
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

internal val canvasEasing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)

@Stable
// Indiachrome's 2.5 Hz critically damped spring preserves velocity when its goal changes.
internal class CanvasMotionValue(
    initial: Float,
) {
    var value by mutableFloatStateOf(initial)
        private set
    private var velocity = 0.0

    fun step(
        goal: Float,
        dt: Double,
    ): Boolean {
        val omega = 2 * PI * 2.5
        val delta = value - goal
        val rate = velocity + omega * delta
        val decay = exp(-omega * dt)
        value = (goal + (delta + rate * dt) * decay).toFloat()
        velocity = (velocity - omega * rate * dt) * decay
        if (abs(value - goal) < 0.0001 && abs(velocity) < 0.0001) {
            value = goal
            velocity = 0.0
        }
        return value != goal || velocity != 0.0
    }
}

@Stable
internal class CanvasPlayerMotion {
    val controls = CanvasMotionValue(0.65f)
    val brightness = CanvasMotionValue(0.78f)
    val lift = CanvasMotionValue(1f)
    var lastActivity by mutableLongStateOf(System.nanoTime())
        private set
    var awake by mutableStateOf(true)
    var pointerInside by mutableStateOf(true)
    var controlsHovered by mutableStateOf(false)
    var controlsFocused by mutableStateOf(false)
    var topControlsHovered by mutableStateOf(false)
    var topControlsFocused by mutableStateOf(false)
    var pointerPressed by mutableStateOf(false)
    var menuOpen by mutableStateOf(false)
    var lyricsMenuOpen by mutableStateOf(false)
    private val popups = mutableStateMapOf<Any, Boolean>()
    val hasOpenPopup: Boolean get() = popups.isNotEmpty()
    val acceptsInput by derivedStateOf { controls.value > 0.01f }

    fun activity() {
        lastActivity = System.nanoTime()
        awake = true
        pointerInside = true
    }

    fun popupVisibilityChanged(
        owner: Any,
        visible: Boolean,
    ) {
        if (visible) {
            popups[owner] = true
        } else if (popups.remove(owner) != null) {
            activity()
        }
    }
}

@Composable
internal fun rememberCanvasPlayerMotion(enabled: Boolean): CanvasPlayerMotion {
    val motion = remember { CanvasPlayerMotion() }
    LaunchedEffect(enabled) {
        if (enabled) motion.activity()
    }
    LaunchedEffect(enabled, motion.lastActivity) {
        if (enabled) {
            delay(750)
            motion.awake = false
        }
    }
    val held =
        motion.controlsHovered || motion.controlsFocused ||
            motion.topControlsHovered || motion.topControlsFocused || motion.pointerPressed ||
            motion.menuOpen || motion.lyricsMenuOpen || motion.hasOpenPopup ||
            UiState.showPersistentPlayerDrawer || PlayerState.showAddToPlaylistSong != null
    val opacityGoal =
        if (!enabled) {
            0.65f
        } else if (held) {
            0.985f
        } else if (motion.pointerInside && motion.awake) {
            0.65f
        } else {
            0f
        }
    val brightnessGoal =
        if (!enabled) {
            0.78f
        } else if (held) {
            0.55f
        } else if (motion.pointerInside && motion.awake) {
            0.78f
        } else {
            1f
        }
    LaunchedEffect(opacityGoal, brightnessGoal) {
        var lastFrame = 0L
        do {
            val moving =
                withFrameNanos { time ->
                    val dt = if (lastFrame == 0L) 0.016 else ((time - lastFrame) / 1_000_000_000.0).coerceIn(0.0, 0.05)
                    lastFrame = time
                    var moving = motion.controls.step(opacityGoal, dt)
                    moving = motion.brightness.step(brightnessGoal, dt) || moving
                    val liftGoal =
                        if (opacityGoal > 0f) {
                            1f
                        } else if (motion.controls.value < 0.01f) {
                            0f
                        } else {
                            motion.lift.value
                        }
                    motion.lift.step(liftGoal, dt) || moving
                }
        } while (moving)
    }
    return motion
}

internal fun Modifier.canvasActivity(
    motion: CanvasPlayerMotion,
    enabled: Boolean,
): Modifier =
    onPreviewKeyEvent {
        if (enabled) motion.activity()
        false
    }.pointerInput(motion, enabled) {
        if (!enabled) return@pointerInput
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                when (event.type) {
                    PointerEventType.Move,
                    PointerEventType.Enter,
                    PointerEventType.Press,
                    PointerEventType.Release,
                    PointerEventType.Scroll,
                    -> motion.activity()

                    PointerEventType.Exit -> motion.pointerInside = false
                }
                motion.pointerPressed = event.changes.any { it.pressed }
            }
        }
    }

internal fun Modifier.canvasControlsHover(
    motion: CanvasPlayerMotion,
    topControls: Boolean = false,
): Modifier =
    onFocusChanged {
        val previous = if (topControls) motion.topControlsFocused else motion.controlsFocused
        if (topControls) motion.topControlsFocused = it.hasFocus else motion.controlsFocused = it.hasFocus
        if (previous != it.hasFocus) motion.activity()
    }.focusGroup()
        .pointerInput(motion) {
            awaitPointerEventScope {
                while (true) {
                    when (awaitPointerEvent(PointerEventPass.Initial).type) {
                        PointerEventType.Enter,
                        PointerEventType.Move,
                        -> if (topControls) motion.topControlsHovered = true else motion.controlsHovered = true

                        PointerEventType.Exit -> if (topControls) motion.topControlsHovered = false else motion.controlsHovered = false
                    }
                }
            }
        }

@Composable
internal fun Modifier.canvasControlsVisibility(motion: CanvasPlayerMotion?): Modifier {
    if (motion == null) return this
    val acceptsInput = motion.acceptsInput
    return graphicsLayer { alpha = motion.controls.value.coerceIn(0f, 1f) }
        .layout { measurable, constraints ->
            val content = measurable.measure(constraints)
            // Reserve the dock height while removing invisible controls from hit testing.
            layout(content.width, content.height) {
                if (acceptsInput) content.place(0, 0)
            }
        }.focusProperties { canFocus = acceptsInput }
        .blockInput(!acceptsInput)
}
