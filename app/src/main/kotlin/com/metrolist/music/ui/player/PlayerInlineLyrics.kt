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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
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
                if (shouldInsertInlineSpaceAfter(index)) append(' ')
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

private fun List<com.metrolist.music.lyrics.WordTimestamp>.shouldInsertInlineSpaceAfter(index: Int): Boolean {
    if (index >= lastIndex) return false
    val word = get(index)
    val next = get(index + 1)
    if (word.hasTrailingSpace) return true

    val currentText = word.text.trimEnd()
    val nextText = next.text.trimStart()
    if (currentText.isEmpty() || nextText.isEmpty()) return false

    val currentLast = currentText.last()
    val nextFirst = nextText.first()
    if (nextFirst in NoLeadingSpaceBefore) return false
    if (currentLast in NoTrailingSpaceAfter) return false
    return true
}

private val NoLeadingSpaceBefore = setOf(',', '.', '!', '?', ';', ':', ')', ']', '}', '\u2026', '%', '\u2019', '\'')
private val NoTrailingSpaceAfter = setOf('(', '[', '{', '\u00bf', '\u00a1', '\u201c', '"', '-')

@Composable
private fun SmoothWrappedLyricLine(
    line: InlineKaraokeLine,
    positionMs: Long,
    textColor: Color,
    style: TextStyle,
) {
    val density = LocalDensity.current
    val fillLeadPx = remember(density) { with(density) { InlineLyricFillLead.toPx() } }
    val sweepVerticalPaddingPx = remember(density) { with(density) { InlineLyricSweepVerticalPadding.toPx() } }
    var layoutResult by remember(line.text) { mutableStateOf<TextLayoutResult?>(null) }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(SmoothInlineLyricsSlotHeight)
                .clipToBounds(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = line.text,
            style =
                style.copy(
                    color = textColor.copy(alpha = 0.40f),
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
            onTextLayout = { layoutResult = it },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = line.text,
            style =
                style.copy(
                    color = textColor.copy(alpha = 0.98f),
                    shadow =
                        Shadow(
                            color = textColor.copy(alpha = 0.48f),
                            offset = Offset.Zero,
                            blurRadius = 20f,
                        ),
                ),
            maxLines = MaxSmoothInlineTextLines,
            softWrap = true,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .drawWithContent {
                        val layout = layoutResult ?: return@drawWithContent
                        line.fillRects(layout, positionMs, fillLeadPx, sweepVerticalPaddingPx).forEach { rect ->
                            clipRect(
                                left = rect.left,
                                top = rect.top,
                                right = rect.right,
                                bottom = rect.bottom,
                            ) {
                                this@drawWithContent.drawContent()
                            }
                        }
                    },
        )
    }
}

private const val MaxSmoothInlineTextLines = 3
private val InlineLyricFillLead = 2.dp
private val InlineLyricSweepVerticalPadding = 3.dp

private data class InlineSweepRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

private fun InlineKaraokeLine.fillRects(
    layout: TextLayoutResult,
    positionMs: Long,
    fillLeadPx: Float,
    verticalPaddingPx: Float,
): List<InlineSweepRect> {
    if (text.isEmpty() || layout.lineCount == 0) return emptyList()

    val textOffset = sweepTextOffset(positionMs).coerceIn(0f, text.length.toFloat())
    val charOffset = textOffset.toInt().coerceIn(0, text.lastIndex)
    val lineIndex = layout.getLineForOffset(charOffset).coerceIn(0, layout.lineCount - 1)
    val lineFillRight = layout.horizontalPositionForOffset(lineIndex, textOffset) + fillLeadPx

    return buildList {
        for (index in 0 until lineIndex) {
            add(layout.fillRectForLine(index, layout.getLineRight(index), verticalPaddingPx))
        }
        add(layout.fillRectForLine(lineIndex, lineFillRight, verticalPaddingPx))
    }
}

private fun InlineKaraokeLine.sweepTextOffset(positionMs: Long): Float {
    if (segments.isEmpty()) return 0f

    var heldOffset = 0f
    segments.forEach { segment ->
        if (positionMs < segment.startMs) return heldOffset

        val segmentEndOffset = visibleEndOffset(segment)
        if (positionMs <= segment.endMs) {
            val progress =
                ((positionMs - segment.startMs).toFloat() / (segment.endMs - segment.startMs).coerceAtLeast(1))
                    .coerceIn(0f, 1f)
            val easedProgress = progress * progress * (3f - 2f * progress)
            return segment.startIndex + ((segmentEndOffset - segment.startIndex) * easedProgress)
        }

        heldOffset = segmentEndOffset
    }

    return heldOffset
}

private fun InlineKaraokeLine.visibleEndOffset(segment: InlineKaraokeSegment): Float {
    var endIndex = segment.endIndex.coerceIn(segment.startIndex, text.length)
    while (endIndex > segment.startIndex && text.getOrNull(endIndex - 1)?.isWhitespace() == true) {
        endIndex--
    }
    return endIndex.toFloat()
}

private fun TextLayoutResult.horizontalPositionForOffset(
    lineIndex: Int,
    textOffset: Float,
): Float {
    val lineLeft = getLineLeft(lineIndex)
    val lineRight = getLineRight(lineIndex).coerceAtLeast(lineLeft + 1f)
    val lineStart = getLineStart(lineIndex)
    val lineEnd = getLineEnd(lineIndex, visibleEnd = true).coerceAtLeast(lineStart)
    if (lineEnd <= lineStart || textOffset <= lineStart) return lineLeft
    if (textOffset >= lineEnd) return lineRight

    val lowerOffset = textOffset.toInt().coerceIn(lineStart, lineEnd - 1)
    val charBounds = getBoundingBox(lowerOffset)
    val charProgress = (textOffset - lowerOffset).coerceIn(0f, 1f)
    return (charBounds.left + (charBounds.width * charProgress)).coerceIn(lineLeft, lineRight)
}

private fun TextLayoutResult.fillRectForLine(
    lineIndex: Int,
    fillRight: Float,
    verticalPaddingPx: Float,
): InlineSweepRect {
    val lineLeft = getLineLeft(lineIndex)
    val lineRight = getLineRight(lineIndex).coerceAtLeast(lineLeft + 1f)
    return InlineSweepRect(
        left = lineLeft,
        top = getLineTop(lineIndex) - verticalPaddingPx,
        right = fillRight.coerceIn(lineLeft, lineRight),
        bottom = getLineBottom(lineIndex) + verticalPaddingPx,
    )
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
        if (timedWords.shouldInsertInlineSpaceAfter(index)) {
            append(" ")
        }
    }
}
