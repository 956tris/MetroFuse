/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.music.lyrics.LyricsEntry
import com.metrolist.music.lyrics.LyricsUtils

internal object AndroidAutoLyrics {
    const val UPDATE_INTERVAL_MS = 250L

    fun enabled(storedValue: Boolean?): Boolean = storedValue ?: true

    data class CurrentLine(
        val index: Int,
        val segmentIndex: Int,
        val text: String,
        val windowStartMs: Long,
        val windowEndMs: Long,
        val segments: List<AutomotiveLyricSegmenter.TimedSegment>,
    )

    fun currentLine(
        lines: List<LyricsEntry>,
        positionMs: Long,
        offsetMs: Long = 0L,
        trackDurationMs: Long? = null,
    ): CurrentLine? {
        if (lines.isEmpty()) return null

        val effectivePosition = (positionMs + offsetMs).coerceAtLeast(0L)
        val activeIndices = LyricsUtils.findActiveLineIndices(lines, effectivePosition)
        val index = activeIndices
            .filterNot { lines[it].isBackground }
            .maxByOrNull { lines[it].time }
            ?: LyricsUtils.findCurrentLineIndex(lines, effectivePosition)
        if (index !in lines.indices) return null

        val text = AutomotiveLyricSegmenter.normalize(lines[index].text)
        if (text.isEmpty()) return null

        val windowStartMs = lines[index].time
        val nextTimestamp = lines.asSequence()
            .drop(index + 1)
            .filterNot { it.isBackground }
            .map { it.time }
            .firstOrNull { it > windowStartMs }
        val trackEndInLyricsTime = trackDurationMs
            ?.takeIf { it > 0L }
            ?.plus(offsetMs)
            ?.takeIf { it > windowStartMs }
        val windowEndMs = nextTimestamp
            ?: trackEndInLyricsTime
            ?: (windowStartMs + AutomotiveLyricSegmenter.LAST_LINE_FALLBACK_DURATION_MS)
        val segments = AutomotiveLyricSegmenter.timedSegments(text, windowStartMs, windowEndMs)
        val segmentIndex = AutomotiveLyricSegmenter.segmentAt(segments, effectivePosition)
        val segment = segments.getOrNull(segmentIndex) ?: return null
        return CurrentLine(
            index = index,
            segmentIndex = segmentIndex,
            text = segment.text,
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            segments = segments,
        )
    }
}
