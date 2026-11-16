/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

internal const val METROFUSE_LYRICS_SOURCE_FORMAT = "metrofuse-api"

@Serializable
private data class MetroFuseLyricsResponse(
    val success: Boolean,
    val data: MetroFuseLyricsData? = null,
)

@Serializable
private data class MetroFuseLyricsData(
    val source: String,
    val syncType: String,
    val trackInfo: MetroFuseLyricsTrack,
    val lines: List<MetroFuseLyricsLine>,
)

@Serializable
private data class MetroFuseLyricsTrack(
    val title: String,
    val artist: String,
)

@Serializable
private data class MetroFuseLyricsLine(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String,
    val words: List<MetroFuseLyricsWord>? = null,
)

@Serializable
private data class MetroFuseLyricsWord(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
)

class MetroFuseLyricsProvider(
    private val httpClient: HttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Long?,
    ): String? {
        if (title.isBlank()) return null
        val body =
            httpClient
                .prepareGet("https://metrofuse-lyrics-worker.amax69420lol.workers.dev/lyrics") {
                    expectMissingLyrics()
                    parameter("title", title.trim())
                    if (artist.isNotBlank()) parameter("artist", artist.trim())
                    duration?.takeIf { it > 0 }?.let { parameter("duration", (it / 1000.0).roundToLong()) }
                    header("Accept", "application/json")
                }.readLyricsText() ?: return null
        val response = json.decodeFromString<MetroFuseLyricsResponse>(body)
        val data = response.data?.takeIf { response.success } ?: return null
        val lines = data.lines.filter { it.text.isNotBlank() }
        if (lines.isEmpty()) return null
        val builder =
            MlxBuilder()
                .meta(source = "MetroFuse (${data.source})", sourceFormat = METROFUSE_LYRICS_SOURCE_FORMAT)
                .song(
                    title = data.trackInfo.title.ifBlank { title },
                    artist = data.trackInfo.artist.ifBlank { artist },
                )
        when (data.syncType) {
            "plain" -> {
                lines.forEach { builder.unsyncedLine(it.text) }
            }

            "word", "line" -> {
                lines.sortedBy { it.startTimeMs }.forEach { line ->
                    require(line.startTimeMs >= 0 && line.endTimeMs > line.startTimeMs) { "Invalid lyrics line timing" }
                    val words =
                        line.words
                            .orEmpty()
                            .takeIf { values ->
                                data.syncType == "word" && values.isNotEmpty() &&
                                    values.all {
                                        it.text.isNotEmpty() && it.startTimeMs >= line.startTimeMs &&
                                            it.endTimeMs > it.startTimeMs && it.endTimeMs <= line.endTimeMs
                                    } && values.zipWithNext().all { (previous, next) -> previous.startTimeMs <= next.startTimeMs }
                            }?.let { values ->
                                alignLyricsWordText(line.text, values.map { it.text })?.let(::separateUnspacedWords)?.mapIndexed {
                                    index,
                                    text,
                                    ->
                                    MlxWord(text, values[index].startTimeMs, values[index].endTimeMs, hasTrailingSpace = false)
                                }
                            }
                    if (words == null) {
                        builder.line(line.startTimeMs, line.endTimeMs, line.text)
                    } else {
                        builder.line(
                            line.startTimeMs,
                            line.endTimeMs,
                            words = words,
                        )
                    }
                }
            }

            else -> {
                error("Unknown lyrics synchronization type")
            }
        }
        return builder.build()
    }
}
