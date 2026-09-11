/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomotiveLyricSegmenterTest {
    @Test
    fun keepsShortLineUnchanged() {
        assertEquals(listOf("A short lyric line"), AutomotiveLyricSegmenter.segmentLine("A short lyric line"))
    }

    @Test
    fun normalizesWhitespaceBeforeSegmenting() {
        val result = AutomotiveLyricSegmenter.segmentLine("  first\n\tphrase    second phrase  ")

        assertEquals("first phrase second phrase", result.joinToString(" "))
        assertTrue(result.none { "  " in it })
    }

    @Test
    fun splitsLongLineAndPreservesEveryWord() {
        val text = "I remember every avenue and every quiet corner where we promised we would never say goodbye"
        val result = AutomotiveLyricSegmenter.segmentLine(text)

        assertTrue(result.size in 2..AutomotiveLyricSegmenter.MAX_SEGMENTS)
        assertEquals(text, result.joinToString(" "))
    }

    @Test
    fun capsVeryLongLineAtMaximumSegmentCount() {
        val text = List(60) { "palavra$it" }.joinToString(" ")
        val result = AutomotiveLyricSegmenter.segmentLine(text)

        assertEquals(AutomotiveLyricSegmenter.MAX_SEGMENTS, result.size)
        assertEquals(text, result.joinToString(" "))
    }

    @Test
    fun prefersPunctuationNearNaturalSplit() {
        val text = "We kept driving in the rain, then watched the whole horizon slowly turn to gold"
        val result = AutomotiveLyricSegmenter.segmentLine(text)

        assertTrue(result.first().endsWith(','))
        assertEquals(text, result.joinToString(" "))
    }

    @Test
    fun preservesAccentsEmojiAndJoinedEmojiSequences() {
        val family = "👨‍👩‍👧‍👦"
        val text = "Coração aceso $family enquanto a canção atravessa a cidade e encontra você outra vez"
        val result = AutomotiveLyricSegmenter.segmentLine(text)

        assertEquals(text, result.joinToString(" "))
        assertTrue(result.any { family in it })
    }

    @Test
    fun preservesGraphemesWhenTextHasNoSpaces() {
        val grapheme = "e\u0301"
        val text = ("音楽で世界をつなぐ" + grapheme).repeat(8)
        val result = AutomotiveLyricSegmenter.segmentLine(text)

        assertEquals(text, result.joinToString(""))
        assertFalse(result.drop(1).any { it.startsWith("\u0301") })
    }

    @Test
    fun doesNotSplitAnUnbrokenLatinWord() {
        val text = "supercalifragilisticexpialidocious".repeat(4)

        assertEquals(listOf(text), AutomotiveLyricSegmenter.segmentLine(text))
    }

    @Test
    fun assignsProportionalTimingAndPreservesOriginalWindow() {
        val text = "A compact opening phrase followed by a considerably longer phrase that carries much more visual weight"
        val result = AutomotiveLyricSegmenter.timedSegments(text, 1_000L, 9_000L)

        assertTrue(result.size >= 2)
        assertEquals(1_000L, result.first().startTimeMs)
        assertEquals(9_000L, result.last().endTimeMs)
        result.zipWithNext().forEach { (current, next) ->
            assertEquals(current.endTimeMs, next.startTimeMs)
        }
        assertTrue(result.all { it.endTimeMs - it.startTimeMs >= AutomotiveLyricSegmenter.MIN_SEGMENT_DURATION_MS })
    }

    @Test
    fun shortTimingWindowDoesNotCreateUnreadableSegments() {
        val text = "This line is long enough to split but its display window is much too short to read"
        val result = AutomotiveLyricSegmenter.timedSegments(text, 1_000L, 2_200L)

        assertEquals(1, result.size)
        assertEquals(text, result.single().text)
    }

    @Test
    fun durationLimitsLineToTwoOrThreeSegments() {
        val text = List(30) { "word$it" }.joinToString(" ")

        assertEquals(2, AutomotiveLyricSegmenter.timedSegments(text, 0L, 2_300L).size)
        assertEquals(3, AutomotiveLyricSegmenter.timedSegments(text, 0L, 3_000L).size)
    }

    @Test
    fun segmentAtMovesExactlyAtCalculatedTransition() {
        val segments = AutomotiveLyricSegmenter.timedSegments(
            "A long first thought followed by another thought that continues across the automotive display",
            5_000L,
            11_000L,
        )
        val transition = segments[1].startTimeMs

        assertEquals(0, AutomotiveLyricSegmenter.segmentAt(segments, transition - 1L))
        assertEquals(1, AutomotiveLyricSegmenter.segmentAt(segments, transition))
    }
}
