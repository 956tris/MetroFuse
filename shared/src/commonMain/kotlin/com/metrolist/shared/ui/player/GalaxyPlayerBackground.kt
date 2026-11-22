/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.metrolist.shared.ui.components.BlurredArtworkBackground
import com.metrolist.shared.ui.theme.toHsv

@Composable
fun GalaxyPlayerBackground(
    artworkUrl: String?,
    artworkColor: Color,
    blurredArtwork: Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
    artworkCrossfade: PlayerArtworkCrossfade = PlayerArtworkCrossfade(),
) {
    val colors = remember(artworkUrl, artworkColor) {
        if (artworkUrl == null) emptyList() else galaxySkyColors(artworkColor)
    }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (blurredArtwork && artworkUrl != null) {
            val blurRadius = with(LocalDensity.current) { 150.dp.toPx() }
            Box(Modifier.fillMaxSize().graphicsLayer()) {
                PlayerBackgroundArtwork(artworkUrl, artworkCrossfade) { url ->
                    BlurredArtworkBackground(url, blurRadiusPx = blurRadius, scrimAlpha = 0.3f)
                }
            }
        }
        GalaxyStarOverlay(
            modifier = Modifier.fillMaxSize(),
            skyColors = colors,
            animated = active,
            skyAlpha = if (blurredArtwork) 0.7f else 1f,
        )
    }
}

// The legacy Galaxy palette used a dark sky tinted by the artwork and a brighter meteor glow.
private fun galaxySkyColors(color: Color): List<Color> {
    val (hue, saturation, value) = color.toHsv()
    if (value < 0.18f || (value < 0.24f && saturation < 0.35f)) return emptyList()
    val tint = if (saturation < 0.12f) saturation * 0.55f else (saturation * 1.65f).coerceIn(0.42f, 0.98f)
    val brightness = (value * 1.35f).coerceIn(0.28f, 0.88f)
    return listOf(
        Color.hsv(hue, tint * 0.82f, brightness * 0.22f),
        Color.hsv(hue, tint * 0.95f, brightness * 0.42f),
        Color.hsv(hue, (tint * 1.05f).coerceAtMost(1f), brightness * 0.88f),
        Color.hsv(hue, tint * 0.62f, (brightness * 1.55f).coerceIn(0.55f, 0.92f)),
    )
}
