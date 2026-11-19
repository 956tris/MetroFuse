package com.metrolist.shared.api.lyrics

import com.metrolist.shared.model.AgentType
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

internal const val SPICY_LYRICS_PROVIDER = "Spicy Lyrics"
internal const val SPICY_LYRICS_SOURCE_FORMAT = "spicy-lyrics-api"

private const val API_URL = "https://api.spicylyrics.org/v1"
private val spotifyId = Regex("[A-Za-z0-9]{22}")

internal class SpicyLyricsProvider(
    private val httpClient: HttpClient,
    private val clientKey: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun getLyrics(
        title: String,
        artist: String,
        duration: Long?,
        album: String?,
    ): String? {
        if (clientKey.isBlank() || title.isBlank() || artist.isBlank()) return null
        val search =
            httpClient
                .prepareGet("$API_URL/tracks/search") {
                    expectMissingLyrics()
                    bearerAuth(clientKey)
                    parameter("title", title.trim().take(200))
                    parameter("artist", artist.trim().take(200))
                    album?.trim()?.takeIf(String::isNotEmpty)?.let { parameter("album", it.take(200)) }
                    duration?.takeIf { it in 1..86_400_000 }?.let { parameter("duration", it / 1_000.0) }
                    parameter("limit", 1)
                }.readLyricsText() ?: return null
        val match =
            json
                .decodeFromString<SpicyEnvelope<SpicySearch>>(search)
                .body
                ?.results
                ?.firstOrNull()
        val id = match?.id?.takeIf { match.tier == "exact" && spotifyId.matches(it) } ?: return null
        val lyrics =
            httpClient
                .prepareGet("$API_URL/lyrics/$id") {
                    expectMissingLyrics()
                    bearerAuth(clientKey)
                }.readLyricsText() ?: return null
        val document = json.decodeFromString<SpicyEnvelope<SpicyDocument>>(lyrics).body ?: return null
        return document.toMlx(title, artist, id)
    }
}

@Serializable
private data class SpicyEnvelope<T>(
    @SerialName("Body") val body: T? = null,
)

@Serializable
private data class SpicySearch(
    val results: List<SpicyMatch> = emptyList(),
)

@Serializable
private data class SpicyMatch(
    val id: String,
    val tier: String,
)

@Serializable
private data class SpicyDocument(
    val source: String? = null,
    @SerialName("Type") val type: String,
    @SerialName("Content") val content: List<SpicyVocal> = emptyList(),
    @SerialName("Lines") val staticLines: List<SpicyVocal> = emptyList(),
    @SerialName("SongWriters") val songwriters: List<String> = emptyList(),
    @SerialName("UploadAttribution") val attribution: SpicyAttribution? = null,
) {
    fun toMlx(
        title: String,
        artist: String,
        id: String,
    ): String? {
        val sourceName =
            when (source) {
                "spicy_lyrics" -> SPICY_LYRICS_PROVIDER
                "apple_music" -> "Apple Music"
                "spotify" -> "Spotify"
                else -> null
            }
        val credits = attribution.takeIf { source == "spicy_lyrics" }
        val builder =
            MlxBuilder()
                .meta(
                    source = sourceName.orEmpty(),
                    sourceId = id,
                    sourceFormat = SPICY_LYRICS_SOURCE_FORMAT,
                    lyricsUploader = credits?.uploader?.username?.singleLine(),
                    lyricsUploaderUrl = credits?.uploader?.url?.singleLine(),
                    lyricsMaker = credits?.maker?.username?.singleLine(),
                    lyricsMakerUrl = credits?.maker?.url?.singleLine(),
                ).song(
                    title = title.singleLine(),
                    artist = artist.singleLine(),
                    songwriters = songwriters.map(String::singleLine),
                )
        if (type == "Syllable") {
            builder.agent("lead", AgentType.PERSON).agent("opposite", AgentType.PERSON)
        }
        var hasLyrics = false
        val vocals = if (type == "Static") staticLines.ifEmpty { content } else content
        for (vocal in vocals) {
            if (vocal.type != null && vocal.type != "Vocal") continue
            if (type == "Syllable") {
                val lead = vocal.lead ?: continue
                val words = lead.words()
                val start = lead.startTime?.toMillis() ?: words.firstOrNull()?.startMs ?: continue
                val end = lead.endTime?.toMillis() ?: words.lastOrNull()?.endMs ?: continue
                if (words.isEmpty() || end <= start) continue
                val background =
                    vocal.background
                        .orEmpty()
                        .flatMap { it.words() }
                        .sortedBy { it.startMs }
                builder.line(start, end, if (vocal.oppositeAligned) "opposite" else "lead", words, background)
            } else {
                val line = vocal.text?.singleLine()?.takeIf(String::isNotBlank) ?: continue
                when (type) {
                    "Line" -> {
                        val start = vocal.startTime?.toMillis() ?: continue
                        val end = vocal.endTime?.toMillis()
                        if (end != null && end > start) builder.line(start, end, line) else builder.line(start, line)
                    }

                    "Static" -> {
                        builder.unsyncedLine(line)
                    }

                    else -> {
                        continue
                    }
                }
            }
            hasLyrics = true
        }
        return builder.build().takeIf { hasLyrics }
    }
}

@Serializable
private data class SpicyAttribution(
    @SerialName("Uploader") val uploader: SpicyContributor? = null,
    @SerialName("Maker") val maker: SpicyContributor? = null,
)

@Serializable
private data class SpicyContributor(
    val username: String? = null,
    val url: String? = null,
)

@Serializable
private data class SpicyVocal(
    @SerialName("Type") val type: String? = null,
    @SerialName("Text") val text: String? = null,
    @SerialName("StartTime") val startTime: Double? = null,
    @SerialName("EndTime") val endTime: Double? = null,
    @SerialName("Lead") val lead: SpicyVoice? = null,
    @SerialName("Background") val background: List<SpicyVoice>? = null,
    @SerialName("OppositeAligned") val oppositeAligned: Boolean = false,
)

@Serializable
private data class SpicyVoice(
    @SerialName("StartTime") val startTime: Double? = null,
    @SerialName("EndTime") val endTime: Double? = null,
    @SerialName("Syllables") val syllables: List<SpicySyllable> = emptyList(),
) {
    fun words(): List<MlxWord> =
        syllables.mapIndexedNotNull { index, syllable ->
            val start = syllable.startTime?.toMillis() ?: return@mapIndexedNotNull null
            val end = syllable.endTime?.toMillis() ?: return@mapIndexedNotNull null
            val text = syllable.text.singleLine().takeIf(String::isNotBlank) ?: return@mapIndexedNotNull null
            if (end <= start) return@mapIndexedNotNull null
            val trailingSpace = !syllable.isPartOfWord && index < syllables.lastIndex
            MlxWord(text, start, end, hasTrailingSpace = trailingSpace)
        }
}

@Serializable
private data class SpicySyllable(
    @SerialName("Text") val text: String = "",
    @SerialName("StartTime") val startTime: Double? = null,
    @SerialName("EndTime") val endTime: Double? = null,
    @SerialName("IsPartOfWord") val isPartOfWord: Boolean = false,
)

private fun Double.toMillis(): Long? = takeIf { it.isFinite() && it >= 0 }?.let { (it * 1_000).roundToLong() }

private fun String.singleLine(): String = replace('\r', ' ').replace('\n', ' ').trim()
