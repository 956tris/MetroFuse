/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.LyricsTimingKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MetroFuseLyricsProviderTest {
    @Test
    fun restoresMissingWordSeparatorsWithoutSeparatingSyllables() =
        runBlocking {
            val text = "You probably think that you're better now, better now."
            val tokens = listOf("You", "pro", "ba", "bly", "think", "that", "you're", "better", "now,", "better", "now.")
            val starts = tokens.indices.map { 18_200L + it * 200 }
            val ends = tokens.indices.map { if (it == tokens.lastIndex) 21_450L else starts[it] + 200 }
            val timedWords =
                tokens
                    .mapIndexed { index, token ->
                        """{"text":${Json.encodeToString(token)},"startTimeMs":${starts[index]},"endTimeMs":${ends[index]}}"""
                    }.joinToString(",", prefix = "[", postfix = "]")
            HttpClient(MockEngine { respond(response("word", timedWords, text)) }).use { client ->
                val raw = assertNotNull(MetroFuseLyricsProvider(client).getLyrics("Better Now", "Post Malone", 231_000))
                val line = LyricsUtils.parseLyrics(raw).single()
                assertEquals(text, line.text)
                val words = assertNotNull(line.words)
                assertEquals(tokens, words.map { it.text })
                assertEquals(listOf(true, false, false, true, true, true, true, true, true, true, false), words.map { it.hasTrailingSpace })
                assertEquals(starts.map { it / 1000.0 }, words.map { it.startTime })
                assertEquals(ends.map { it / 1000.0 }, words.map { it.endTime })
            }
        }

    @Test
    fun preservesRepeatedAndUnicodeWhitespaceFromTheFullLine() =
        runBlocking {
            val text = "Say  hello,\tI'm\u00a0back!"
            val timedWords =
                """[{"text":"Say","startTimeMs":18200,"endTimeMs":18600},
                   {"text":"hello,","startTimeMs":18600,"endTimeMs":19100},
                   {"text":"I'm","startTimeMs":19100,"endTimeMs":20000},
                   {"text":"back!","startTimeMs":20000,"endTimeMs":21450}]"""
            HttpClient(MockEngine { respond(response("word", timedWords, text)) }).use { client ->
                val raw = assertNotNull(MetroFuseLyricsProvider(client).getLyrics("Song", "Artist", null))
                val line = LyricsUtils.parseLyrics(raw).single()
                assertEquals(text, line.text)
                val words = assertNotNull(line.words)
                assertEquals(text, words.joinToString("") { it.text + if (it.hasTrailingSpace) " " else "" })
            }
        }

    @Test
    fun retainsTheFullLineWhenWordTextDoesNotMatch() =
        runBlocking {
            HttpClient(
                MockEngine {
                    respond(response("word", """[{"text":"Different lyrics","startTimeMs":18200,"endTimeMs":21450}]"""))
                },
            ).use { client ->
                val raw = assertNotNull(MetroFuseLyricsProvider(client).getLyrics("Song", "Artist", null))
                val line = LyricsUtils.parseLyrics(raw).single()
                assertEquals("Brother 石头", line.text)
                assertNull(line.words)
                assertEquals(18_200L, line.time)
                assertEquals(21_450L, line.endTime)
            }
        }

    @Test
    fun sendsMetadataAndPreservesWordTimingAndSyllableSpacing() =
        runBlocking {
            HttpClient(
                MockEngine { request ->
                    assertEquals("metrofuse-lyrics-worker.amax69420lol.workers.dev", request.url.host)
                    assertEquals("/lyrics", request.url.encodedPath)
                    assertEquals("Song & Night", request.url.parameters["title"])
                    assertEquals("Artist / Guest", request.url.parameters["artist"])
                    assertEquals("196", request.url.parameters["duration"])
                    respond(
                        response(
                            "word",
                            """
                [{"text":"Brother ","startTimeMs":18200,"endTimeMs":18600},
                 {"text":"石","startTimeMs":18600,"endTimeMs":19100},
                 {"text":"头","startTimeMs":19100,"endTimeMs":21450}]
            """,
                        ),
                    )
                },
            ).use { client ->
                val raw = assertNotNull(MetroFuseLyricsProvider(client).getLyrics("Song & Night", "Artist / Guest", 196_000L))
                val line = LyricsUtils.parseLyrics(raw).single()
                assertEquals("Brother 石头", line.text)
                assertEquals(18_200L, line.time)
                assertEquals(21_450L, line.endTime)
                val words = assertNotNull(line.words)
                assertEquals(listOf("Brother", "石", "头"), words.map { it.text })
                assertEquals(listOf(true, false, false), words.map { it.hasTrailingSpace })
                assertEquals(listOf(18.2, 18.6, 19.1), words.map { it.startTime })
                assertEquals(listOf(18.6, 19.1, 21.45), words.map { it.endTime })
                assertEquals(LyricsTimingKind.WORD_SYNCED, lyricsTimingKind(listOf(line)))
            }
        }

    @Test
    fun preservesLineEndsAndLeavesPlainLyricsUnsynced() =
        runBlocking {
            for (syncType in listOf("line", "plain")) {
                HttpClient(MockEngine { respond(response(syncType)) }).use { client ->
                    val raw = assertNotNull(MetroFuseLyricsProvider(client).getLyrics("Song", "Artist", null))
                    val lines = LyricsUtils.parseLyrics(raw)
                    val line = lines.single()
                    assertEquals("Brother 石头", line.text)
                    assertNull(line.words)
                    if (syncType == "line") {
                        assertEquals(18_200L, line.time)
                        assertEquals(21_450L, line.endTime)
                        assertEquals(LyricsTimingKind.LINE_SYNCED, lyricsTimingKind(lines))
                    } else {
                        assertEquals(LyricsTimingKind.UNSYNCED, lyricsTimingKind(lines))
                    }
                }
            }
        }

    @Test
    fun fallsBackToLineTimingWhenWordTimingIsInvalid() =
        runBlocking {
            HttpClient(
                MockEngine {
                    respond(response("word", """[{"text":"Brother 石头","startTimeMs":18200,"endTimeMs":17000}]"""))
                },
            ).use { client ->
                val raw = assertNotNull(MetroFuseLyricsProvider(client).getLyrics("Song", "Artist", null))
                val line = LyricsUtils.parseLyrics(raw).single()
                assertEquals("Brother 石头", line.text)
                assertNull(line.words)
                assertEquals(18_200L, line.time)
                assertEquals(21_450L, line.endTime)
            }
        }

    @Test
    fun handlesMissingLyricsAndPreservesCancellation(): Unit =
        runBlocking {
            HttpClient(MockEngine { respond("""{"error":"No lyrics found"}""", HttpStatusCode.NotFound) }).use { client ->
                assertNull(MetroFuseLyricsProvider(client).getLyrics("Song", "Artist", null))
            }
            HttpClient(MockEngine { throw CancellationException("cancelled") }).use { client ->
                assertFailsWith<CancellationException> { MetroFuseLyricsProvider(client).getLyrics("Song", "Artist", null) }
            }
        }

    private fun response(
        syncType: String,
        words: String = "null",
        text: String = "Brother 石头",
    ) = """
        {"success":true,"source_tier":"kv_persistent","data":{
          "source":"kugou","syncType":"$syncType",
          "trackInfo":{"title":"Song","artist":"Artist","durationMs":196000},
          "lines":[{"startTimeMs":18200,"endTimeMs":21450,"text":${Json.encodeToString(text)},"words":$words}]
        }}
    """
}
