/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import com.metrolist.music.ui.screens.settings.AppearanceSettings
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A blurred album-art background that slowly pans and zoomes, replicating the
 * "living wallpaper" effect seen on premium web music players.
 *
 * The album art is rendered larger than the viewport (scale 1.15–1.3) and
 * blurred heavily (150 dp), then drifted on a slow elliptical path so the
 * background never feels static. A dark scrim keeps foreground content legible.
 *
 * Cross-fades between tracks with the same 800 ms tween used by the other
 * background styles.
 *
 * Requires API 31+ (Android S) because [Modifier.blur] uses a hardware
 * RenderEffect on older versions — [AppearanceSettings] filters this style out
 * for pre-S devices.
 */
@Composable
fun MovingBlurBackground(
    artworkUrl: String?,
    useDarkTheme: Boolean,
    backgroundAlpha: Float,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "movingBlur")

    // Scale breathes between 1.25 and 1.45 over ~12 s.
    val scale by transition.animateFloat(
        initialValue = 1.25f,
        targetValue = 1.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 12_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "movingBlurScale",
    )

    // Two independent phase values drive the elliptical pan so X and Y don't
    // move in lock-step — looks more organic than a synced diagonal drift.
    val phaseX by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 15_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "movingBlurPhaseX",
    )
    val phaseY by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 21_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "movingBlurPhaseY",
    )

    // Maximum pan distance in dp.
    val panRangeDp = 42.dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(backgroundAlpha)
    ) {
        androidx.compose.animation.AnimatedContent(
            targetState = artworkUrl,
            transitionSpec = {
                fadeIn(tween(800)).togetherWith(fadeOut(tween(800)))
            },
            label = "movingBlurCrossfade",
        ) { url ->
            if (url != null) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(url)
                            .size(100, 100)
                            .allowHardware(false)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(if (useDarkTheme) 150.dp else 110.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = (panRangeDp * cos(phaseX.toDouble()).toFloat()).toPx()
                                translationY = (panRangeDp * sin(phaseY.toDouble()).toFloat()).toPx()
                            },
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = if (useDarkTheme) 0.42f else 0.28f)),
                    )
                }
            }
        }
    }
}
