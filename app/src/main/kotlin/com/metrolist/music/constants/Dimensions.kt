/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.constants

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

const val CONTENT_TYPE_HEADER = 0
const val CONTENT_TYPE_LIST = 1
const val CONTENT_TYPE_SONG = 2
const val CONTENT_TYPE_ARTIST = 3
const val CONTENT_TYPE_ALBUM = 4
const val CONTENT_TYPE_PLAYLIST = 5

val NavigationBarHeight = 80.dp
val SlimNavBarHeight = 64.dp
val MiniPlayerHeight = 64.dp
val MinMiniPlayerHeight = 16.dp
val MiniPlayerBottomSpacing = 8.dp // Space between MiniPlayer and NavigationBar
val QueuePeekHeight = 64.dp
val AppBarHeight = 64.dp

val ListItemHeight = 64.dp
val SuggestionItemHeight = 56.dp
val SearchFilterHeight = 48.dp
val ListThumbnailSize = 48.dp
val SmallGridThumbnailHeight = 104.dp
val GridThumbnailHeight = 128.dp
val AlbumThumbnailSize = 144.dp

val ThumbnailCornerRadius = 3.dp

val PlayerHorizontalPadding = 32.dp

val NavigationBarAnimationSpec = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow
)

val BottomSheetAnimationSpec = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow
)

val BottomSheetSoftAnimationSpec = spring<Dp>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow
)

/**
 * Single shared motion system: every screen, sheet, list and lyric animation
 * should use these durations/easings/specs instead of inline magic numbers,
 * so motion stays consistent and only needs tuning in one place.
 * All offsets run through graphicsLayer (translation/alpha/scale) at the call
 * sites — never layout properties — to avoid re-layout jank.
 */
object Motion {
    const val FastMs = 180
    const val MediumMs = 250
    const val SlowMs = 420
    const val ScrollMs = 750

    val StandardEasing = FastOutSlowInEasing
    val Linear = LinearEasing

    fun <T> fastTween(delayMs: Int = 0) = tween<T>(durationMillis = FastMs, delayMillis = delayMs, easing = StandardEasing)
    fun <T> mediumTween(delayMs: Int = 0) = tween<T>(durationMillis = MediumMs, delayMillis = delayMs, easing = StandardEasing)
    fun <T> slowTween(delayMs: Int = 0) = tween<T>(durationMillis = SlowMs, delayMillis = delayMs, easing = StandardEasing)

    // Lyric line change: slow slide + quick fade, shared by inline/full lyrics.
    val LyricLineEnter =
        slideInVertically(tween(durationMillis = SlowMs, easing = StandardEasing), initialOffsetY = { it / 2 }) +
            fadeIn(tween(durationMillis = 220, delayMillis = 90, easing = StandardEasing))
    val LyricLineExit =
        slideOutVertically(tween(durationMillis = 260, easing = StandardEasing), targetOffsetY = { -it / 2 }) +
            fadeOut(tween(durationMillis = FastMs))
}
