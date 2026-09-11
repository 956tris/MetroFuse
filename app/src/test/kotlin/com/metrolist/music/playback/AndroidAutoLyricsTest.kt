/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import com.metrolist.music.lyrics.LyricsEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutoLyricsTest {
    private val lines = listOf(
        LyricsEntry(1_000L, "First line"),
        LyricsEntry(2_000L, "Second line"),
        LyricsEntry(3_000L, "   "),
    )

    @Test
    fun `synchronized lyrics default on but respect explicit off`() {
        assertTrue(AndroidAutoLyrics.enabled(null))
        assertTrue(AndroidAutoLyrics.enabled(true))
        assertFalse(AndroidAutoLyrics.enabled(false))
    }

    @Test
    fun returnsNullBeforeFirstTimestamp() {
        assertNull(AndroidAutoLyrics.currentLine(lines, 500L))
    }

    @Test
    fun followsCurrentLineAcrossSeek() {
        val secondLine = AndroidAutoLyrics.currentLine(lines, 2_500L)
        assertEquals(1, secondLine?.index)
        assertEquals(0, secondLine?.segmentIndex)
        assertEquals("Second line", secondLine?.text)

        val firstLine = AndroidAutoLyrics.currentLine(lines, 1_200L)
        assertEquals(0, firstLine?.index)
        assertEquals(0, firstLine?.segmentIndex)
        assertEquals("First line", firstLine?.text)
    }

    @Test
    fun appliesStoredLyricsOffset() {
        val currentLine = AndroidAutoLyrics.currentLine(lines, 1_600L, offsetMs = 500L)
        assertEquals(1, currentLine?.index)
        assertEquals("Second line", currentLine?.text)
    }

    @Test
    fun blankCurrentLineRestoresNormalSubtitle() {
        assertNull(AndroidAutoLyrics.currentLine(lines, 3_100L))
    }

    @Test
    fun normalizesWhitespaceForSingleLineCarDisplays() {
        val spaced = listOf(LyricsEntry(0L, "one\n  two\tthree"))
        assertEquals(
            "one two three",
            AndroidAutoLyrics.currentLine(spaced, 0L)?.text,
        )
    }

    @Test
    fun prefersMainVocalOverSimultaneousBackgroundLine() {
        val simultaneous = listOf(
            LyricsEntry(1_000L, "Main vocal", isBackground = false),
            LyricsEntry(1_000L, "Background vocal", isBackground = true),
            LyricsEntry(2_000L, "Next line", isBackground = false),
        )
        assertEquals(
            "Main vocal",
            AndroidAutoLyrics.currentLine(simultaneous, 1_500L)?.text,
        )
    }

    @Test
    fun segmentsLongSubtitleWithoutDiscardingText() {
        val text = "This is a long lyric line that should be displayed as several readable pieces without losing any words"
        val longLine = listOf(
            LyricsEntry(0L, text),
            LyricsEntry(8_000L, "Next line"),
        )

        val currentLine = AndroidAutoLyrics.currentLine(longLine, 0L)

        assertEquals(0, currentLine?.segmentIndex)
        assertEquals(text, currentLine?.segments?.joinToString(" ") { it.text })
        assertEquals(8_000L, currentLine?.windowEndMs)
    }

    @Test
    fun usesPlayerPositionToSelectSegmentAfterSeek() {
        val text = "First readable phrase, followed by another phrase, and a final phrase for the driver"
        val longLine = listOf(
            LyricsEntry(1_000L, text),
            LyricsEntry(9_000L, "Next line"),
        )
        val first = AndroidAutoLyrics.currentLine(longLine, 1_000L)!!
        val secondStart = first.segments[1].startTimeMs

        val afterSeek = AndroidAutoLyrics.currentLine(longLine, secondStart + 1L)

        assertEquals(1, afterSeek?.segmentIndex)
        assertEquals(first.segments[1].text, afterSeek?.text)
    }

    @Test
    fun usesTrackDurationForLastLineAndFallsBackWhenUnknown() {
        val lastLine = listOf(LyricsEntry(2_000L, "A sufficiently long final lyric line that needs more than one display segment"))

        assertEquals(12_000L, AndroidAutoLyrics.currentLine(lastLine, 2_000L, trackDurationMs = 12_000L)?.windowEndMs)
        assertEquals(
            2_000L + AutomotiveLyricSegmenter.LAST_LINE_FALLBACK_DURATION_MS,
            AndroidAutoLyrics.currentLine(lastLine, 2_000L)?.windowEndMs,
        )
    }
}
