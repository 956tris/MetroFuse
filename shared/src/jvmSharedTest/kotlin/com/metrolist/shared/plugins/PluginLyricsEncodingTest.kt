package com.metrolist.shared.plugins

import com.metrolist.shared.api.lyrics.LyricsUtils
import dev.metrofuse.api.LyricsLine
import dev.metrofuse.api.LyricsResult
import dev.metrofuse.api.LyricsWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PluginLyricsEncodingTest {
    @Test
    fun pluginLyricsRoundTripThroughTheBuiltInLyricsParser() {
        val line =
            LyricsLine(
                text = "Hello \"world\"",
                timeMs = 1234,
                words = listOf(LyricsWord("Hello", 1234, 1567), LyricsWord("\"world\"", 1567, 2345)),
            )
        val encoded = LyricsResult(listOf(line), source = "spotify.lyrics", language = "en").toHostLyrics("Song", "Artist", 180_000)
        val lyrics = assertNotNull(LyricsUtils.parseMlxDocument(encoded.lyrics))
        assertEquals("spotify.lyrics", encoded.provider)
        assertEquals(encoded.provider, lyrics.meta.lyricSource)
        assertEquals(encoded.provider, lyrics.meta.provider)
        assertEquals("Song", lyrics.song.title)
        assertEquals("Artist", lyrics.song.artist)
        assertEquals(180_000L, lyrics.song.durationMs)
        assertEquals("en", lyrics.song.language)
        val decoded = lyrics.lines.single()
        assertEquals(line.text, decoded.text)
        assertEquals(1234L, decoded.time)
        assertEquals(2345L, decoded.endTime)
        val words = assertNotNull(decoded.words)
        assertEquals(listOf("Hello", "\"world\""), words.map { it.text })
        assertEquals(listOf(1.234, 1.567), words.map { it.startTime })
        assertEquals(listOf(1.567, 2.345), words.map { it.endTime })
    }

    @Test
    fun untimedPluginLyricsStayUntimed() {
        val encoded = LyricsResult(listOf(LyricsLine("First line"), LyricsLine("Second line"))).toHostLyrics("Song", "Artist", null)
        val lines = LyricsUtils.parseLyrics(encoded.lyrics)
        assertEquals(listOf("First line", "Second line"), lines.map { it.text })
        assertFalse(LyricsUtils.hasPlaybackTiming(lines))
        lines.forEach { assertNull(it.words) }
    }
}
