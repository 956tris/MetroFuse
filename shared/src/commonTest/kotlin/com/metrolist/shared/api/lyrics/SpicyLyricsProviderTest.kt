package com.metrolist.shared.api.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SpicyLyricsProviderTest {
    @Test
    fun matchesMetadataAndKeepsSyllableTimingAndCommunityCredits() =
        runTest {
            HttpClient(
                MockEngine { request ->
                    assertEquals("api.spicylyrics.org", request.url.host)
                    assertEquals("Bearer sl_pk_test", request.headers[HttpHeaders.Authorization])
                    assertNull(request.headers[HttpHeaders.Origin])
                    when (request.url.encodedPath) {
                        "/v1/tracks/search" -> {
                            assertEquals("Song & Night", request.url.parameters["title"])
                            assertEquals("Artist", request.url.parameters["artist"])
                            assertEquals("Album", request.url.parameters["album"])
                            assertEquals("196.125", request.url.parameters["duration"])
                            respond(search("exact"))
                        }

                        "/v1/lyrics/$TRACK_ID" -> {
                            respond(
                                """
                                {"Body":{"Type":"Syllable","source":"spicy_lyrics",
                                  "UploadAttribution":{
                                    "Uploader":{"username":"Uploader","url":"https://spicylyrics.org/uid/1"},
                                    "Maker":{"username":"Maker","url":"https://spicylyrics.org/uid/2"}},
                                  "Content":[{"Type":"Vocal","Lead":{"StartTime":1.25,"EndTime":3,"Syllables":[
                                    {"Text":"Hel","StartTime":1.25,"EndTime":1.5,"IsPartOfWord":true},
                                    {"Text":"lo","StartTime":1.5,"EndTime":2},
                                    {"Text":"world","StartTime":2,"EndTime":3}]}}]}}
                                """.trimIndent(),
                            )
                        }

                        else -> {
                            error("Unexpected lyrics request")
                        }
                    }
                },
            ).use { client ->
                val raw = assertNotNull(SpicyLyricsProvider(client, "sl_pk_test").getLyrics("Song & Night", "Artist", 196_125, "Album"))
                val document = assertNotNull(LyricsUtils.parseMlxDocument(raw))
                assertEquals("Uploader", document.meta.lyricsUploader)
                assertEquals("https://spicylyrics.org/uid/2", document.meta.lyricsMakerUrl)
                assertEquals(TRACK_ID, document.meta.sourceId)
                val line = document.lines.single()
                assertEquals("Hello world", line.text)
                assertEquals(1_250L, line.time)
                assertEquals(listOf(1.25, 1.5, 2.0), line.words?.map { it.startTime })
            }
        }

    @Test
    fun skipsAlternateVersionsAndFallsThroughOnMissingLyrics() =
        runTest {
            for (tier in listOf("version", "exact")) {
                val paths = mutableListOf<String>()
                HttpClient(
                    MockEngine { request ->
                        paths += request.url.encodedPath
                        if (request.url.encodedPath == "/v1/tracks/search") {
                            respond(search(tier))
                        } else {
                            respond("", HttpStatusCode.NotFound)
                        }
                    },
                ).use { client ->
                    assertNull(SpicyLyricsProvider(client, "sl_pk_test").getLyrics("Song", "Artist", null, null))
                }
                val expected = if (tier == "exact") listOf("/v1/tracks/search", "/v1/lyrics/$TRACK_ID") else listOf("/v1/tracks/search")
                assertEquals(expected, paths)
            }
        }

    private fun search(tier: String): String = """{"Body":{"results":[{"tier":"$tier","id":"$TRACK_ID"}]}}"""

    private companion object {
        const val TRACK_ID = "4PTG3Z6ehGkBFwjybzWkR8"
    }
}
