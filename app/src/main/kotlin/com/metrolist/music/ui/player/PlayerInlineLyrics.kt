/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsUtils
import kotlinx.coroutines.isActive

@Composable
internal fun PlayerInlineLyrics(
    lyricsEntity: LyricsEntity?,
    positionMs: Long,
    textColor: Color,
    smoothSlidingLine: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current
    val isPlaying by playerConnection
        ?.isEffectivelyPlaying
        ?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(false) }
    var smoothPositionMs by remember { mutableLongStateOf(positionMs) }
    var positionAnchorMs by remember { mutableLongStateOf(positionMs) }
    var timeAnchorMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(positionMs) {
        positionAnchorMs = positionMs
        timeAnchorMs = SystemClock.elapsedRealtime()
        smoothPositionMs = positionMs
    }

    val lyricsEntries =
        remember(lyricsEntity?.id, lyricsEntity?.lyrics) {
            lyricsEntity
                ?.lyrics
                ?.takeUnless { it == LyricsEntity.LYRICS_NOT_FOUND }
                ?.let(LyricsUtils::parseLyrics)
                ?.filter { it.text.isNotBlank() }
                .orEmpty()
        }

    LaunchedEffect(isPlaying, lyricsEntries.isNotEmpty()) {
        if (!isPlaying || lyricsEntries.isEmpty()) {
            smoothPositionMs = positionAnchorMs
            return@LaunchedEffect
        }

        while (isActive) {
            withFrameMillis {
                smoothPositionMs = positionAnchorMs + (SystemClock.elapsedRealtime() - timeAnchorMs)
            }
        }
    }

    val lyricLines = remember(lyricsEntries, smoothPositionMs) {
        lyricsEntries.currentInlineLines(smoothPositionMs)
    }
    val hasWordTimings = remember(lyricsEntries) {
        lyricsEntries.any { !it.words.isNullOrEmpty() }
    }
    val smoothLine = remember(lyricsEntries, smoothPositionMs, smoothSlidingLine, hasWordTimings) {
        if (smoothSlidingLine && hasWordTimings) {
            lyricsEntries.currentSmoothInlineLine(smoothPositionMs)
        } else {
            null
        }
    }
    val useSmoothSlidingLine = smoothLine != null

    Box(
        modifier =
            modifier
                .height(if (useSmoothSlidingLine) SmoothInlineLyricsSlotHeight else InlineLyricsSlotHeight)
                .clipToBounds(),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (useSmoothSlidingLine) {
            AnimatedContent(
                targetState = smoothLine,
                transitionSpec = {
                    (
                        slideInVertically(
                            animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                            initialOffsetY = { it / 2 },
                        ) + fadeIn(animationSpec = tween(durationMillis = 220, delayMillis = 90))
                    ).togetherWith(
                        slideOutVertically(
                            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                            targetOffsetY = { -it / 2 },
                        ) + fadeOut(animationSpec = tween(durationMillis = 180)),
                    )
                },
                label = "PlayerInlineLyricsSmoothLine",
            ) { line ->
                SmoothWrappedLyricLine(
                    line = line,
                    positionMs = smoothPositionMs,
                    textColor = textColor,
                    style =
                        MaterialTheme.typography.titleLarge.copy(
                            fontSize = if (line.entry.isBackground) 15.sp else 18.sp,
                            lineHeight = if (line.entry.isBackground) 19.sp else 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            fontStyle = if (line.entry.isBackground) FontStyle.Italic else FontStyle.Normal,
                            textAlign = TextAlign.Start,
                            color = textColor,
                        ),
                )
            }
        } else {
            AnimatedContent(
                targetState = lyricLines,
                transitionSpec = {
                    (
                        slideInVertically(
                            animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                            initialOffsetY = { it / 2 },
                        ) + fadeIn(animationSpec = tween(durationMillis = 220, delayMillis = 90))
                    ).togetherWith(
                        slideOutVertically(
                            animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                            targetOffsetY = { -it / 2 },
                        ) + fadeOut(animationSpec = tween(durationMillis = 180)),
                    )
                },
                label = "PlayerInlineLyricsLine",
            ) { lines ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    lines.forEach { line ->
                        Text(
                            text = line.inlineLyricsText(smoothPositionMs, textColor),
                            style =
                                MaterialTheme.typography.titleLarge.copy(
                                    fontSize = if (line.isBackground) 14.sp else 17.sp,
                                    lineHeight = if (line.isBackground) 18.sp else 21.sp,
                                    fontWeight = if (line.isBackground) FontWeight.Bold else FontWeight.ExtraBold,
                                    fontStyle = if (line.isBackground) FontStyle.Italic else FontStyle.Normal,
                                    textAlign = if (line.isBackground) TextAlign.Center else TextAlign.Start,
                                    color = textColor,
                                ),
                            maxLines = if (lines.size > 1) 1 else MaxInlineTextLines,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

private fun List<LyricsEntry>.currentInlineLines(positionMs: Long): List<LyricsEntry> {
    if (isEmpty()) return emptyList()

    val activeIndices = LyricsUtils.findActiveLineIndices(this, positionMs).toMutableSet()
    activeIndices.toList().forEach { index ->
        if (getOrNull(index)?.isBackground == true) {
            val pairedMainIndex = (index - 1 downTo 0).firstOrNull { getOrNull(it)?.isBackground == false }
            if (pairedMainIndex != null) activeIndices.add(pairedMainIndex)
        }
    }

    val activeLines = activeIndices.sorted().mapNotNull(::getOrNull).filter { it.text.isNotBlank() }
    if (activeLines.isNotEmpty()) {
        return activeLines.takeLast(MaxInlineLyricLines)
    }

    val foregroundEntries = filterNot { it.isBackground }.ifEmpty { this }
    val adjustedPosition = positionMs + 120L
    val currentIndex = foregroundEntries.indexOfLast { it.time <= adjustedPosition }

    return foregroundEntries.getOrNull(currentIndex)?.let(::listOf).orEmpty()
}

private const val MaxInlineLyricLines = 3
private const val MaxInlineTextLines = 3
private val InlineLyricsSlotHeight = 68.dp
private val SmoothInlineLyricsSlotHeight = 76.dp

private data class InlineKaraokeLine(
    val entry: LyricsEntry,
    val text: String,
    val segments: List<InlineKaraokeSegment>,
)

private data class InlineKaraokeSegment(
    val startIndex: Int,
    val endIndex: Int,
    val startMs: Long,
    val endMs: Long,
)

private fun List<LyricsEntry>.currentSmoothInlineLine(positionMs: Long): InlineKaraokeLine? {
    if (isEmpty()) return null

    val activeIndices = LyricsUtils.findActiveLineIndices(this, positionMs)
    val selectedIndex =
        activeIndices
            .filter { index ->
                getOrNull(index)?.let { line -> line.text.isNotBlank() && !line.isBackground } == true
            }.maxByOrNull { index -> get(index).time }
            ?: activeIndices
                .filter { index -> getOrNull(index)?.text?.isNotBlank() == true }
                .maxByOrNull { index -> get(index).time }
            ?: indices
                .filter { index ->
                    val line = get(index)
                    line.time <= positionMs + 120L && line.text.isNotBlank() && !line.isBackground
                }.maxByOrNull { index -> get(index).time }
            ?: indices.firstOrNull { index -> get(index).text.isNotBlank() && !get(index).isBackground }
            ?: indices.firstOrNull { index -> get(index).text.isNotBlank() }
            ?: return null

    val entry = get(selectedIndex)
    val segments = entry.words?.toInlineKaraokeSegments().orEmpty()
    if (segments.isEmpty()) return null

    return InlineKaraokeLine(
        entry = entry,
        text = segments.joinToString(separator = "") { segment -> segment.text },
        segments = segments.map { segment ->
            InlineKaraokeSegment(
                startIndex = segment.startIndex,
                endIndex = segment.endIndex,
                startMs = segment.startMs,
                endMs = segment.endMs,
            )
        },
    )
}

private data class InlineKaraokeTextSegment(
    val text: String,
    val startIndex: Int,
    val endIndex: Int,
    val startMs: Long,
    val endMs: Long,
)

private fun List<com.metrolist.music.lyrics.WordTimestamp>.toInlineKaraokeSegments(): List<InlineKaraokeTextSegment> {
    var textIndex = 0
    return mapIndexedNotNull { index, word ->
        val segmentText =
            buildString {
                append(word.text.replace('\n', ' '))
                if (word.hasTrailingSpace && index < lastIndex) append(' ')
            }
        if (segmentText.isEmpty()) return@mapIndexedNotNull null
        val startIndex = textIndex
        textIndex += segmentText.length
        val startMs = (word.startTime * 1000).toLong()
        val endMs =
            (word.endTime * 1000)
                .toLong()
                .takeIf { it > startMs }
                ?: (startMs + (word.text.length * 70L).coerceAtLeast(280L))
        InlineKaraokeTextSegment(
            text = segmentText,
            startIndex = startIndex,
            endIndex = textIndex,
            startMs = startMs,
            endMs = endMs,
        )
    }
}

@Composable
private fun SmoothWrappedLyricLine(
    line: InlineKaraokeLine,
    positionMs: Long,
    textColor: Color,
    style: TextStyle,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(SmoothInlineLyricsSlotHeight)
                .clipToBounds(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = line.smoothInlineLyricsText(positionMs, textColor),
            style =
                style.copy(
                    color = textColor.copy(alpha = 0.38f),
                    shadow =
                        Shadow(
                            color = textColor.copy(alpha = 0.10f),
                            offset = Offset.Zero,
                            blurRadius = 8f,
                        ),
                ),
            maxLines = MaxSmoothInlineTextLines,
            softWrap = true,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val MaxSmoothInlineTextLines = 3

private fun InlineKaraokeLine.smoothInlineLyricsText(
    positionMs: Long,
    textColor: Color,
) = buildAnnotatedString {
    segments.forEach { segment ->
        val segmentText = text.substring(segment.startIndex, segment.endIndex)
        val isActive = positionMs in segment.startMs..segment.endMs
        val hasPassed = positionMs > segment.endMs
        val progress =
            if (isActive) {
                ((positionMs - segment.startMs).toFloat() / (segment.endMs - segment.startMs).coerceAtLeast(1))
                    .coerceIn(0f, 1f)
            } else {
                0f
            }
        val easedProgress = progress * progress * (3f - 2f * progress)
        val color =
            when {
                hasPassed -> textColor.copy(alpha = 0.96f)
                isActive -> textColor.copy(alpha = 0.70f + (0.26f * easedProgress))
                else -> textColor.copy(alpha = 0.34f)
            }
        val shadow =
            when {
                isActive -> {
                    Shadow(
                        color = textColor.copy(alpha = 0.26f + (0.28f * easedProgress)),
                        offset = Offset.Zero,
                        blurRadius = 12f + (12f * easedProgress),
                    )
                }

                hasPassed -> {
                    Shadow(
                        color = textColor.copy(alpha = 0.14f),
                        offset = Offset.Zero,
                        blurRadius = 7f,
                    )
                }

                else -> null
            }

        withStyle(
            SpanStyle(
                color = color,
                fontWeight = if (isActive) FontWeight.Black else FontWeight.ExtraBold,
                shadow = shadow,
            ),
        ) {
            append(segmentText)
        }
    }
}

private fun LyricsEntry.inlineLyricsText(
    positionMs: Long,
    textColor: Color,
) = buildAnnotatedString {
    val timedWords = words?.takeIf { it.isNotEmpty() }

    if (timedWords == null) {
        append(text)
        return@buildAnnotatedString
    }

    timedWords.forEachIndexed { index, word ->
        val wordStartMs = (word.startTime * 1000).toLong()
        val wordEndMs = (word.endTime * 1000).toLong().takeIf { it > wordStartMs } ?: (wordStartMs + 450L)
        val isActive = positionMs in wordStartMs..wordEndMs
        val hasPassed = positionMs > wordEndMs
        val rawProgress =
            when {
                hasPassed -> 1f
                isActive -> ((positionMs - wordStartMs).toFloat() / (wordEndMs - wordStartMs).coerceAtLeast(1)).coerceIn(0f, 1f)
                else -> 0f
            }
        val smoothProgress = rawProgress * rawProgress * (3f - 2f * rawProgress)
        val wordColor =
            when {
                hasPassed -> textColor
                isActive -> textColor.copy(alpha = 0.55f + (0.45f * smoothProgress))
                else -> textColor.copy(alpha = 0.35f)
            }
        val wordWeight =
            when {
                isActive -> FontWeight.Black
                hasPassed -> FontWeight.ExtraBold
                else -> FontWeight.Bold
            }
        val wordShadow =
            when {
                isActive -> {
                    Shadow(
                        color = textColor.copy(alpha = 0.18f + (0.18f * smoothProgress)),
                        offset = Offset.Zero,
                        blurRadius = 8f + (8f * smoothProgress),
                    )
                }

                hasPassed -> {
                    Shadow(
                        color = textColor.copy(alpha = 0.12f),
                        offset = Offset.Zero,
                        blurRadius = 6f,
                    )
                }

                else -> null
            }

        withStyle(SpanStyle(color = wordColor, fontWeight = wordWeight, shadow = wordShadow)) {
            append(word.text)
        }
        if (word.hasTrailingSpace && index < timedWords.lastIndex) {
            append(" ")
        }
    }
}
