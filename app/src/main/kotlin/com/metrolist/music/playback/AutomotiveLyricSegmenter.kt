/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import java.text.BreakIterator
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

internal object AutomotiveLyricSegmenter {
    const val MAX_VISUAL_WIDTH = 30.0
    const val MIN_SEGMENT_DURATION_MS = 800L
    const val MAX_SEGMENTS = 4
    const val LAST_LINE_FALLBACK_DURATION_MS = 5_000L

    data class TimedSegment(
        val text: String,
        val startTimeMs: Long,
        val endTimeMs: Long,
    )

    fun normalize(text: String): String = text.replace(WHITESPACE_REGEX, " ").trim()

    fun estimateVisualWidth(text: String): Double {
        var width = 0.0
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            width += codePointWeight(codePoint)
            index += Character.charCount(codePoint)
        }
        return width
    }

    fun segmentLine(
        text: String,
        maxSegments: Int = MAX_SEGMENTS,
    ): List<String> {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return emptyList()

        val totalWidth = estimateVisualWidth(normalized)
        if (totalWidth <= MAX_VISUAL_WIDTH || maxSegments <= 1) return listOf(normalized)

        val segmentCount = ceil(totalWidth / MAX_VISUAL_WIDTH)
            .toInt()
            .coerceIn(2, maxSegments.coerceIn(1, MAX_SEGMENTS))
        val boundaries = safeBoundaries(normalized)
        if (boundaries.isEmpty()) return listOf(normalized)

        val result = mutableListOf<String>()
        var start = 0
        repeat(segmentCount - 1) { segmentIndex ->
            val remainingSegments = segmentCount - segmentIndex
            val remainingWidth = estimateVisualWidth(normalized.substring(start))
            val targetWidth = remainingWidth / remainingSegments
            val candidates = boundaries.filter { it > start && it < normalized.length }
            val boundary = candidates.minByOrNull { candidate ->
                val candidateText = normalized.substring(start, candidate).trim()
                if (candidateText.isEmpty()) {
                    Double.MAX_VALUE
                } else {
                    val width = estimateVisualWidth(candidateText)
                    val overflowPenalty = (width - MAX_VISUAL_WIDTH).coerceAtLeast(0.0) * 8.0
                    val punctuationBonus = if (endsAtNaturalPause(normalized, candidate)) 3.0 else 0.0
                    abs(width - targetWidth) + overflowPenalty - punctuationBonus
                }
            } ?: return@repeat

            val segment = normalized.substring(start, boundary).trim()
            if (segment.isNotEmpty()) result += segment
            start = boundary
            while (start < normalized.length && normalized[start].isWhitespace()) start++
        }

        normalized.substring(start).trim().takeIf { it.isNotEmpty() }?.let(result::add)
        return result.ifEmpty { listOf(normalized) }
    }

    fun timedSegments(
        text: String,
        startTimeMs: Long,
        endTimeMs: Long,
    ): List<TimedSegment> {
        val durationMs = (endTimeMs - startTimeMs).coerceAtLeast(0L)
        val maxSegmentsForWindow =
            if (durationMs < MIN_SEGMENT_DURATION_MS * 2L) {
                1
            } else {
                (durationMs / MIN_SEGMENT_DURATION_MS).toInt().coerceIn(1, MAX_SEGMENTS)
            }
        val segments = segmentLine(text, maxSegmentsForWindow)
        if (segments.isEmpty()) return emptyList()
        if (segments.size == 1 || durationMs <= 0L) {
            return listOf(TimedSegment(segments.first(), startTimeMs, endTimeMs.coerceAtLeast(startTimeMs)))
        }

        val weights = segments.map(::estimateVisualWidth)
        val totalWeight = weights.sum().coerceAtLeast(1.0)
        val distributableMs = durationMs - MIN_SEGMENT_DURATION_MS * segments.size
        var cursor = startTimeMs
        var allocatedExtraMs = 0L

        return segments.mapIndexed { index, segment ->
            val segmentEnd =
                if (index == segments.lastIndex) {
                    endTimeMs
                } else {
                    val cumulativeWeight = weights.take(index + 1).sum()
                    val cumulativeExtra = (distributableMs * cumulativeWeight / totalWeight).toLong()
                    val extraForSegment = cumulativeExtra - allocatedExtraMs
                    allocatedExtraMs = cumulativeExtra
                    cursor + MIN_SEGMENT_DURATION_MS + extraForSegment
                }
            TimedSegment(segment, cursor, segmentEnd).also { cursor = segmentEnd }
        }
    }

    fun segmentAt(
        segments: List<TimedSegment>,
        positionMs: Long,
    ): Int {
        if (segments.isEmpty()) return -1
        val index = segments.indexOfLast { positionMs >= it.startTimeMs }
        return index.coerceAtLeast(0)
    }

    private fun safeBoundaries(text: String): List<Int> {
        val lineBoundaries = mutableSetOf<Int>()
        val lineIterator = BreakIterator.getLineInstance(Locale.ROOT).apply { setText(text) }
        var boundary = lineIterator.first()
        while (boundary != BreakIterator.DONE) {
            if (boundary in 1 until text.length && isSafeUnicodeBoundary(text, boundary)) {
                lineBoundaries += boundary
            }
            boundary = lineIterator.next()
        }
        if (lineBoundaries.isNotEmpty()) return lineBoundaries.sorted()
        if (text.codePoints().noneMatch { isWideUnicode(it) || isEmoji(it) }) return emptyList()

        val characterBoundaries = mutableListOf<Int>()
        val characterIterator = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        boundary = characterIterator.first()
        while (boundary != BreakIterator.DONE) {
            if (boundary in 1 until text.length && isSafeUnicodeBoundary(text, boundary)) {
                characterBoundaries += boundary
            }
            boundary = characterIterator.next()
        }
        return characterBoundaries
    }

    private fun isSafeUnicodeBoundary(text: String, index: Int): Boolean {
        if (index <= 0 || index >= text.length) return false
        if (Character.isHighSurrogate(text[index - 1]) && Character.isLowSurrogate(text[index])) return false

        val previous = text.codePointBefore(index)
        val next = text.codePointAt(index)
        if (previous == ZERO_WIDTH_JOINER || next == ZERO_WIDTH_JOINER) return false
        if (isCombining(next) || isVariationSelector(next) || isEmojiModifier(next)) return false
        if (isRegionalIndicator(previous) && isRegionalIndicator(next)) return false
        return true
    }

    private fun codePointWeight(codePoint: Int): Double =
        when {
            Character.isWhitespace(codePoint) -> 0.55
            isCombining(codePoint) || isVariationSelector(codePoint) || codePoint == ZERO_WIDTH_JOINER -> 0.0
            codePoint in NARROW_CHARACTERS -> 0.55
            codePoint in WIDE_CHARACTERS -> 1.45
            isWideUnicode(codePoint) || isEmoji(codePoint) -> 1.75
            Character.isLetterOrDigit(codePoint) -> 1.0
            else -> 0.7
        }

    private fun endsAtNaturalPause(text: String, boundary: Int): Boolean {
        var index = boundary
        while (index > 0 && text[index - 1].isWhitespace()) index--
        if (index <= 0) return false
        return text.codePointBefore(index) in NATURAL_PAUSES
    }

    private fun isCombining(codePoint: Int): Boolean =
        Character.getType(codePoint) in setOf(
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
        )

    private fun isVariationSelector(codePoint: Int): Boolean =
        codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF

    private fun isEmojiModifier(codePoint: Int): Boolean = codePoint in 0x1F3FB..0x1F3FF

    private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

    private fun isEmoji(codePoint: Int): Boolean =
        codePoint in 0x1F000..0x1FAFF || codePoint in 0x2600..0x27BF

    private fun isWideUnicode(codePoint: Int): Boolean =
        codePoint in 0x1100..0x11FF ||
            codePoint in 0x2E80..0xA4CF ||
            codePoint in 0xAC00..0xD7AF ||
            codePoint in 0xF900..0xFAFF ||
            codePoint in 0xFE10..0xFE6F ||
            codePoint in 0xFF00..0xFFEF

    private val NARROW_CHARACTERS = "ilIjtfr.,:;!'|`´()[]{}".codePoints().toArray().toSet()
    private val WIDE_CHARACTERS = "WMwm@#%&QO".codePoints().toArray().toSet()
    private val NATURAL_PAUSES = ",.;:!?…—–-".codePoints().toArray().toSet()
    private const val ZERO_WIDTH_JOINER = 0x200D
    private val WHITESPACE_REGEX = Regex("\\s+")
}
