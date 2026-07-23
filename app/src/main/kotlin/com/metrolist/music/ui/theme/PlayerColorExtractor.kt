/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Player color extraction system for generating gradients from album artwork
 * 
 * This system analyzes album artwork and extracts vibrant, dominant colors
 * to create visually appealing gradients for the music player interface.
 */
object PlayerColorExtractor {

    /**
     * Extracts colors from a palette and creates a gradient
     * 
     * @param palette The color palette extracted from album artwork
     * @param fallbackColor Fallback color to use if extraction fails
     * @return List of colors for gradient (primary, darker variant, black)
     */
    suspend fun extractGradientColors(
        palette: Palette,
        fallbackColor: Int
    ): List<Color> = withContext(Dispatchers.Default) {
        
        // Extract all available colors with priority for dominant colors
        val colorCandidates = listOfNotNull(
            palette.dominantSwatch, // High priority for dominant color
            palette.vibrantSwatch,
            palette.darkVibrantSwatch,
            palette.lightVibrantSwatch,
            palette.mutedSwatch,
            palette.darkMutedSwatch,
            palette.lightMutedSwatch
        )

        // Select best color based on weight (dominance + vibrancy)
        val bestSwatch = colorCandidates.maxByOrNull { calculateColorWeight(it) }
        val fallbackDominant = palette.dominantSwatch?.rgb?.let { Color(it) }
            ?: Color(palette.getDominantColor(fallbackColor))

        val primaryColor = if (bestSwatch != null) {
            val bestColor = Color(bestSwatch.rgb)
            // Ensure the color is suitable for use
            if (isColorVibrant(bestColor)) {
                enhanceColorVividness(bestColor, 1.3f)
            } else {
                // If not vibrant, use dominant color with slight enhancement
                enhanceColorVividness(fallbackDominant, 1.1f)
            }
        } else {
            enhanceColorVividness(fallbackDominant, 1.1f)
        }
        
        // Create sophisticated gradient with 3 color points
        listOf(
            primaryColor, // Start: primary vibrant color
            primaryColor.copy(
                red = (primaryColor.red * 0.6f).coerceAtLeast(0f),
                green = (primaryColor.green * 0.6f).coerceAtLeast(0f),
                blue = (primaryColor.blue * 0.6f).coerceAtLeast(0f)
            ), // Middle: darker version of primary color
            Color.Black // End: black
        )
    }

    suspend fun extractGalaxyColors(
        palette: Palette,
        fallbackColor: Int,
    ): List<Color> = withContext(Dispatchers.Default) {
        val dominantColor = palette.getDominantColor(fallbackColor)
        val dominantHsv = FloatArray(3)
        android.graphics.Color.colorToHSV(dominantColor, dominantHsv)

        if (dominantHsv[2] < 0.18f || (dominantHsv[2] < 0.24f && dominantHsv[1] < 0.35f)) {
            return@withContext listOf(
                Color.Black,
                Color(0xFF020202),
                Color.Black,
                Color.White,
            )
        }

        val bestSwatch =
            listOfNotNull(
                palette.dominantSwatch,
                palette.darkMutedSwatch,
                palette.darkVibrantSwatch,
                palette.mutedSwatch,
                palette.vibrantSwatch,
            ).maxByOrNull { swatch ->
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(swatch.rgb, hsv)
                swatch.population * (0.45f + hsv[1]) * (1.15f - abs(hsv[2] - 0.38f)).coerceIn(0.25f, 1f)
            }

        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(bestSwatch?.rgb ?: dominantColor, hsv)
        val saturation =
            if (hsv[1] < 0.12f) {
                hsv[1] * 0.55f
            } else {
                (hsv[1] * 1.65f).coerceIn(0.42f, 0.98f)
            }
        val anchorValue = (hsv[2] * 1.35f).coerceIn(0.28f, 0.88f)
        val hue = hsv[0]

        listOf(
            hsvColor(hue, saturation * 0.82f, anchorValue * 0.22f),
            hsvColor(hue, saturation * 0.95f, anchorValue * 0.42f),
            hsvColor(hue, saturation * 1.05f, anchorValue * 0.88f),
            hsvColor(hue, saturation * 0.62f, (anchorValue * 1.55f).coerceIn(0.55f, 0.92f)),
        )
    }

    suspend fun extractMirroredGalaxyColors(
        palette: Palette,
        fallbackColor: Int,
    ): List<Color> = withContext(Dispatchers.Default) {
        val fallback = fallbackColor.toGalaxyCandidate(population = 1)
        val candidates =
            listOfNotNull(
                palette.dominantSwatch,
                palette.vibrantSwatch,
                palette.darkVibrantSwatch,
                palette.lightVibrantSwatch,
                palette.mutedSwatch,
                palette.darkMutedSwatch,
                palette.lightMutedSwatch,
            ).map { it.toGalaxyCandidate() }
                .ifEmpty { listOf(fallback) }

        var totalWeight = 0f
        var weightedSaturation = 0f
        var weightedValue = 0f
        var darkWeight = 0f
        candidates.forEach { candidate ->
            val weight = candidate.population.coerceAtLeast(1).toFloat()
            totalWeight += weight
            weightedSaturation += candidate.saturation * weight
            weightedValue += candidate.value * weight
            if (candidate.value < 0.16f) {
                darkWeight += weight
            }
        }
        val averageSaturation = weightedSaturation / totalWeight.coerceAtLeast(1f)
        val averageValue = weightedValue / totalWeight.coerceAtLeast(1f)
        val darkShare = darkWeight / totalWeight.coerceAtLeast(1f)

        if (averageValue < 0.10f || (averageSaturation < 0.08f && averageValue < 0.24f)) {
            return@withContext listOf(
                Color.Black,
                Color(0xFF020202),
                Color.Black,
                Color.White,
            )
        }

        val primary =
            candidates.maxByOrNull {
                val valueScore = (1.1f - abs(it.value - 0.46f)).coerceIn(0.28f, 1f)
                it.population * (0.42f + it.saturation * 1.25f) * valueScore
            } ?: fallback
        val secondary =
            candidates
                .filter {
                    hueDistance(it.hue, primary.hue) > 16f &&
                        it.saturation > 0.10f &&
                        it.value > 0.08f
                }.maxByOrNull {
                    it.population * (0.30f + it.saturation) * (0.45f + it.value)
                }
                ?: primary.shiftHue(if (primary.saturation > 0.18f) 18f else 0f)
        val accent =
            candidates.maxByOrNull {
                it.population * (0.25f + it.saturation * 1.4f) * (0.35f + it.value)
            } ?: primary

        val darkness = (0.08f + darkShare * 0.06f).coerceIn(0.08f, 0.16f)
        val topValue = (averageValue * 0.18f + 0.035f - darkness * 0.04f).coerceIn(0.025f, 0.12f)
        val midValue = (averageValue * 0.32f + 0.055f - darkness * 0.03f).coerceIn(0.06f, 0.22f)
        val lowValue = (averageValue * 0.48f + 0.075f - darkness * 0.01f).coerceIn(0.10f, 0.32f)
        val primarySaturation = (primary.saturation * 1.15f + averageSaturation * 0.35f).coerceIn(0.35f, 0.98f)
        val secondarySaturation = (secondary.saturation * 1.05f + averageSaturation * 0.32f).coerceIn(0.32f, 0.95f)
        val accentSaturation = (accent.saturation * 1.35f + averageSaturation * 0.25f).coerceIn(0.45f, 1.0f)

        listOf(
            hsvColor(primary.hue, primarySaturation * 0.70f, topValue * 1.4f),
            blendColor(
                hsvColor(primary.hue, primarySaturation, midValue * 1.25f),
                hsvColor(secondary.hue, secondarySaturation, midValue * 1.35f),
                0.38f,
            ),
            blendColor(
                hsvColor(secondary.hue, secondarySaturation, lowValue * 1.15f),
                hsvColor(primary.hue, primarySaturation * 0.75f, lowValue * 1.1f),
                0.22f,
            ),
            hsvColor(
                accent.hue,
                accentSaturation,
                (accent.value * 0.65f + averageValue * 0.30f + 0.45f).coerceIn(0.55f, 0.98f),
            ),
        )
    }

    /**
     * Determines if a color is vibrant enough for use in player UI
     * 
     * @param color The color to analyze
     * @return true if the color has sufficient saturation and brightness
     */
    private fun isColorVibrant(color: Color): Boolean {
        val argb = color.toArgb()
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(argb, hsv)
        val saturation = hsv[1] // HSV[1] is saturation
        val brightness = hsv[2] // HSV[2] is brightness
        
        // Color is vibrant if it has sufficient saturation and appropriate brightness
        // Avoid colors that are too dark or too bright
        return saturation > 0.25f && brightness > 0.2f && brightness < 0.9f
    }
    
    /**
     * Enhances color vividness by adjusting saturation and brightness
     * 
     * @param color The color to enhance
     * @param saturationFactor Factor to multiply saturation by (default 1.4)
     * @return Enhanced color with improved vividness
     */
    private fun enhanceColorVividness(color: Color, saturationFactor: Float = 1.4f): Color {
        val argb = color.toArgb()
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(argb, hsv)
        
        // Increase saturation for more vivid colors
        hsv[1] = (hsv[1] * saturationFactor).coerceAtMost(1.0f)
        // Adjust brightness for better visibility
        hsv[2] = (hsv[2] * 0.9f).coerceIn(0.4f, 0.85f)
        
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    /**
     * Calculates weight for color selection based on dominance and vibrancy
     * 
     * @param swatch The palette swatch to analyze
     * @return Weight value for color selection priority
     */
    private fun calculateColorWeight(swatch: Palette.Swatch?): Float {
        if (swatch == null) return 0f
        val population = swatch.population.toFloat()
        val color = Color(swatch.rgb)
        val argb = color.toArgb()
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(argb, hsv)
        val saturation = hsv[1]
        val brightness = hsv[2]
        
        // Give higher priority to dominance (population) while considering vibrancy
        val populationWeight = population * 2f // Double dominance weight
        val vibrancyBonus = if (saturation > 0.3f && brightness > 0.3f) 1.5f else 1f
        
        return populationWeight * vibrancyBonus * (saturation + brightness) / 2f
    }

    private data class GalaxyColorCandidate(
        val hue: Float,
        val saturation: Float,
        val value: Float,
        val population: Int,
    )

    private fun Palette.Swatch.toGalaxyCandidate(): GalaxyColorCandidate =
        rgb.toGalaxyCandidate(population)

    private fun Int.toGalaxyCandidate(population: Int): GalaxyColorCandidate {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(this, hsv)
        return GalaxyColorCandidate(
            hue = hsv[0],
            saturation = hsv[1],
            value = hsv[2],
            population = population,
        )
    }

    private fun GalaxyColorCandidate.shiftHue(amount: Float): GalaxyColorCandidate =
        copy(hue = (hue + amount).floorHue())

    private fun Float.floorHue(): Float {
        val value = this % 360f
        return if (value < 0f) value + 360f else value
    }

    private fun hueDistance(
        first: Float,
        second: Float,
    ): Float {
        val distance = abs(first.floorHue() - second.floorHue())
        return minOf(distance, 360f - distance)
    }

    private fun blendColor(
        start: Color,
        end: Color,
        fraction: Float,
    ): Color {
        val amount = fraction.coerceIn(0f, 1f)
        val inverse = 1f - amount
        return Color(
            red = start.red * inverse + end.red * amount,
            green = start.green * inverse + end.green * amount,
            blue = start.blue * inverse + end.blue * amount,
            alpha = start.alpha * inverse + end.alpha * amount,
        )
    }

    private fun hsvColor(
        hue: Float,
        saturation: Float,
        value: Float,
    ): Color =
        Color(
            android.graphics.Color.HSVToColor(
                floatArrayOf(
                    hue,
                    saturation.coerceIn(0f, 1f),
                    value.coerceIn(0f, 1f),
                ),
            ),
        )

    /**
     * Configuration constants for color extraction
     */
    object Config {
        const val MAX_COLOR_COUNT = 32
        const val BITMAP_AREA = 8000
        const val IMAGE_SIZE = 200
        
        // Color enhancement factors
        const val VIBRANT_SATURATION_THRESHOLD = 0.25f
        const val VIBRANT_BRIGHTNESS_MIN = 0.2f
        const val VIBRANT_BRIGHTNESS_MAX = 0.9f
        
        const val POPULATION_WEIGHT_MULTIPLIER = 2f
        const val VIBRANCY_THRESHOLD_SATURATION = 0.3f
        const val VIBRANCY_THRESHOLD_BRIGHTNESS = 0.3f
        const val VIBRANCY_BONUS = 1.5f
        
        const val DEFAULT_SATURATION_FACTOR = 1.4f
        const val VIBRANT_SATURATION_FACTOR = 1.3f
        const val FALLBACK_SATURATION_FACTOR = 1.1f
        
        const val BRIGHTNESS_MULTIPLIER = 0.9f
        const val BRIGHTNESS_MIN = 0.4f
        const val BRIGHTNESS_MAX = 0.85f
        
        const val DARKER_VARIANT_FACTOR = 0.6f
    }
}
