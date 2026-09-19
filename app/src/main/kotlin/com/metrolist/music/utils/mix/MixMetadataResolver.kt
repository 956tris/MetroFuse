/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils.mix

import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.spotify.SpotifyCanvasClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class MixMetadata(
    val bpm: Float? = null,
    val keySignature: String? = null,
    val source: String = SOURCE_RECCOBEATS,
)

object MixMetadataResolver {
    suspend fun resolve(
        mediaMetadata: MediaMetadata,
        spotifyCookie: String,
    ): MixMetadata? {
        if (mediaMetadata.isEpisode || mediaMetadata.isVideoSong) return null

        mediaMetadata.directReccoIds().let { ids ->
            ReccoBeatsClient.resolveByIds(ids, mediaMetadata, SOURCE_DIRECT_RECCOBEATS)?.let { return it }
        }

        ProviderIsrcClient.resolve(mediaMetadata)?.let { isrc ->
            ReccoBeatsClient.resolveByIds(listOf(isrc), mediaMetadata, SOURCE_PROVIDER_ISRC)?.let { return it }
        }

        MusicBrainzClient.resolveIsrcs(mediaMetadata).let { isrcs ->
            ReccoBeatsClient.resolveByIds(isrcs, mediaMetadata, SOURCE_MUSICBRAINZ_ISRC)?.let { return it }
        }

        val spotifyTrackId =
            mediaMetadata.id.spotifyTrackId()
                ?: SpotifyCanvasClient
                    .resolveTrackUriForMix(mediaMetadata, spotifyCookie)
                    ?.spotifyTrackId()
        return ReccoBeatsClient.resolveByIds(
            ids = listOfNotNull(spotifyTrackId),
            mediaMetadata = mediaMetadata,
            source = SOURCE_SPOTIFY_FALLBACK,
        )
    }
}

private object ReccoBeatsClient {
    private const val BASE_URL = "https://api.reccobeats.com"
    private const val CACHE_TTL_MS = 1000L * 60L * 60L * 24L

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, CachedMixMetadata>()

    suspend fun resolveByIds(
        ids: List<String>,
        mediaMetadata: MediaMetadata,
        source: String,
    ): MixMetadata? {
        val normalizedIds =
            ids
                .mapNotNull { it.reccoLookupId() }
                .distinct()
                .take(MAX_LOOKUP_IDS)
        if (normalizedIds.isEmpty()) return null

        val cacheKey = "${source}:${mediaMetadata.matchKey}:${normalizedIds.joinToString("|")}"
        val now = System.currentTimeMillis()
        cache[cacheKey]
            ?.takeIf { now - it.cachedAt < CACHE_TTL_MS }
            ?.let { return it.value }

        val result =
            runCatching {
                val track = lookupTracks(normalizedIds).bestMatch(mediaMetadata) ?: return@runCatching null
                resolveAudioFeatures(track.id, source)
            }.onFailure { error ->
                Timber.tag("Automix").d(error, "ReccoBeats metadata lookup failed for ${mediaMetadata.title}")
            }.getOrNull()

        cache[cacheKey] = CachedMixMetadata(result, now)
        return result
    }

    private suspend fun lookupTracks(ids: List<String>): List<ReccoTrack> =
        withContext(Dispatchers.IO) {
            val urlBuilder =
                "$BASE_URL/v1/track"
                    .toHttpUrl()
                    .newBuilder()
            ids.forEach { urlBuilder.addQueryParameter("ids", it) }

            val request =
                Request
                    .Builder()
                    .url(urlBuilder.build())
                    .header("Accept", "application/json")
                    .get()
                    .build()

            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    Timber.tag("Automix").d("ReccoBeats track lookup failed: ${response.code} ${body.take(120)}")
                    return@withContext emptyList()
                }

                json
                    .parseToJsonElement(body)
                    .jsonObject
                    .array("content")
                    .orEmpty()
                    .mapNotNull { it.obj?.toReccoTrack() }
            }
        }

    private suspend fun resolveAudioFeatures(
        reccoTrackId: String,
        source: String,
    ): MixMetadata? =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url("$BASE_URL/v1/track/$reccoTrackId/audio-features")
                    .header("Accept", "application/json")
                    .get()
                    .build()

            client.newCall(request).execute().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    Timber.tag("Automix").d("ReccoBeats audio features failed: ${response.code} ${body.take(120)}")
                    return@withContext null
                }

                val root = json.parseToJsonElement(body).jsonObject
                MixMetadata(
                    bpm = root.double("tempo")?.toFloat()?.takeIf { it in 40f..240f },
                    keySignature = spotifyPitchKey(root.int("key"), root.int("mode")),
                    source = source,
                ).takeIf { it.bpm != null || it.keySignature != null }
            }
        }

    private fun JsonObject.toReccoTrack(): ReccoTrack? =
        ReccoTrack(
            id = string("id") ?: return null,
            title = string("trackTitle").orEmpty(),
            artists =
                array("artists")
                    .orEmpty()
                    .mapNotNull { it.obj?.string("name") },
            durationMs = long("durationMs"),
            isrc = string("isrc"),
            popularity = int("popularity") ?: 0,
        )

    private fun List<ReccoTrack>.bestMatch(mediaMetadata: MediaMetadata): ReccoTrack? =
        maxByOrNull { track ->
            var score = track.popularity.coerceIn(0, 100) / 5
            val targetTitle = mediaMetadata.title.matchText()
            val candidateTitle = track.title.matchText()
            val targetArtists = mediaMetadata.artists.map { it.name.matchText() }.filter { it.isNotBlank() }
            val candidateArtists = track.artists.map { it.matchText() }

            if (targetTitle == candidateTitle) score += 70
            if (candidateTitle.contains(targetTitle) || targetTitle.contains(candidateTitle)) score += 25
            if (targetArtists.any { artist -> candidateArtists.any { it.contains(artist) || artist.contains(it) } }) score += 45

            val targetDuration = mediaMetadata.duration.takeIf { it > 0 }?.times(1000L)
            val candidateDuration = track.durationMs
            if (targetDuration != null && candidateDuration != null) {
                val delta = abs(targetDuration - candidateDuration)
                score += when {
                    delta <= 3_000L -> 35
                    delta <= 8_000L -> 20
                    delta <= 15_000L -> 8
                    else -> 0
                }
            }

            score
        }?.takeIf { track ->
            track.title.matchText() == mediaMetadata.title.matchText() ||
                track.artists.any { candidate ->
                    mediaMetadata.artists.any { it.name.matchText().let(candidate.matchText()::contains) }
                }
        }

    private data class ReccoTrack(
        val id: String,
        val title: String,
        val artists: List<String>,
        val durationMs: Long?,
        val isrc: String?,
        val popularity: Int,
    )

    private data class CachedMixMetadata(
        val value: MixMetadata?,
        val cachedAt: Long,
    )
}

private object  ProviderIsrcClient {
    private const val CACHE_TTL_MS = 1000L * 60L * 60L * 24L

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, CachedIsrc>()

    suspend fun resolve(mediaMetadata: MediaMetadata): String? {
        val deezerId = mediaMetadata.id.deezerTrackId() ?: return null
        val now = System.currentTimeMillis()
        cache[deezerId]
            ?.takeIf { now - it.cachedAt < CACHE_TTL_MS }
            ?.let { return it.value }

        val result =
            runCatching {
                withContext(Dispatchers.IO) {
                    val request =
                        Request
                            .Builder()
                            .url("https://api.deezer.com/track/$deezerId")
                            .header("Accept", "application/json")
                            .get()
                            .build()

                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@withContext null
                        json
                            .parseToJsonElement(response.body.string())
                            .jsonObject
                            .string("isrc")
                            ?.isrc()
                    }
                }
            }.onFailure { error ->
                Timber.tag("Automix").d(error, "Provider ISRC lookup failed for ${mediaMetadata.id}")
            }.getOrNull()

        cache[deezerId] = CachedIsrc(result, now)
        return result
    }

    private data class CachedIsrc(
        val value: String?,
        val cachedAt: Long,
    )
}

private object MusicBrainzClient {
    private const val BASE_URL = "https://musicbrainz.org/ws/2/recording/"
    private const val USER_AGENT = "MetroFuse/1.0 (https://github.com/956tris/MetroFuse)"
    private const val CACHE_TTL_MS = 1000L * 60L * 60L * 24L * 7L

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, CachedIsrcs>()
    private val requestMutex = Mutex()
    private var lastRequestAt = 0L

    suspend fun resolveIsrcs(mediaMetadata: MediaMetadata): List<String> {
        val title = mediaMetadata.title.cleanRecordingQuery()
        val artist = mediaMetadata.artists.firstOrNull()?.name?.cleanRecordingQuery().orEmpty()
        if (title.isBlank() || artist.isBlank()) return emptyList()

        val cacheKey = mediaMetadata.matchKey
        val now = System.currentTimeMillis()
        cache[cacheKey]
            ?.takeIf { now - it.cachedAt < CACHE_TTL_MS }
            ?.let { return it.value }

        val result =
            runCatching {
                val recordings = searchRecordings(title, artist)
                recordings
                    .sortedByDescending { it.scoreFor(mediaMetadata) }
                    .filter { it.scoreFor(mediaMetadata) >= 125 }
                    .flatMap { it.isrcs }
                    .mapNotNull { it.isrc() }
                    .distinct()
                    .take(MAX_LOOKUP_IDS)
            }.onFailure { error ->
                Timber.tag("Automix").d(error, "MusicBrainz ISRC lookup failed for ${mediaMetadata.title}")
            }.getOrElse { emptyList() }

        cache[cacheKey] = CachedIsrcs(result, now)
        return result
    }

    private suspend fun searchRecordings(
        title: String,
        artist: String,
    ): List<MusicBrainzRecording> =
        requestMutex.withLock {
            val elapsed = System.currentTimeMillis() - lastRequestAt
            if (elapsed in 0 until 1100L) delay(1100L - elapsed)

            val response =
                withContext(Dispatchers.IO) {
                    val query = """recording:"${title.luceneQuoted()}" AND artist:"${artist.luceneQuoted()}""""
                    val url =
                        BASE_URL
                            .toHttpUrl()
                            .newBuilder()
                            .addQueryParameter("query", query)
                            .addQueryParameter("fmt", "json")
                            .addQueryParameter("limit", "8")
                            .addQueryParameter("inc", "isrcs")
                            .build()

                    val request =
                        Request
                            .Builder()
                            .url(url)
                            .header("Accept", "application/json")
                            .header("User-Agent", USER_AGENT)
                            .get()
                            .build()

                    client.newCall(request).execute().use { response ->
                        lastRequestAt = System.currentTimeMillis()
                        val body = response.body.string()
                        if (!response.isSuccessful) {
                            Timber.tag("Automix").d("MusicBrainz lookup failed: ${response.code} ${body.take(120)}")
                            return@withContext emptyList()
                        }

                        json
                            .parseToJsonElement(body)
                            .jsonObject
                            .array("recordings")
                            .orEmpty()
                            .mapNotNull { it.obj?.toMusicBrainzRecording() }
                    }
                }
            response
        }

    private fun JsonObject.toMusicBrainzRecording(): MusicBrainzRecording? =
        MusicBrainzRecording(
            title = string("title") ?: return null,
            artists =
                array("artist-credit")
                    .orEmpty()
                    .mapNotNull { credit ->
                        credit.obj?.string("name")
                            ?: credit.obj?.obj("artist")?.string("name")
                    },
            lengthMs = long("length"),
            isrcs =
                array("isrcs")
                    .orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull },
            score = int("score") ?: 0,
        )

    private fun MusicBrainzRecording.scoreFor(mediaMetadata: MediaMetadata): Int {
        var score = this.score
        val targetTitle = mediaMetadata.title.matchText()
        val candidateTitle = title.matchText()
        val targetArtists = mediaMetadata.artists.map { it.name.matchText() }.filter { it.isNotBlank() }
        val candidateArtists = artists.map { it.matchText() }

        if (targetTitle == candidateTitle) score += 80
        if (candidateTitle.contains(targetTitle) || targetTitle.contains(candidateTitle)) score += 20
        if (targetArtists.any { artist -> candidateArtists.any { it.contains(artist) || artist.contains(it) } }) score += 50

        val targetDuration = mediaMetadata.duration.takeIf { it > 0 }?.times(1000L)
        if (targetDuration != null && lengthMs != null) {
            val delta = abs(targetDuration - lengthMs)
            score += when {
                delta <= 3_000L -> 35
                delta <= 8_000L -> 20
                delta <= 15_000L -> 8
                else -> -20
            }
        }

        if (isrcs.isEmpty()) score -= 80
        return score
    }

    private data class MusicBrainzRecording(
        val title: String,
        val artists: List<String>,
        val lengthMs: Long?,
        val isrcs: List<String>,
        val score: Int,
    )

    private data class CachedIsrcs(
        val value: List<String>,
        val cachedAt: Long,
    )
}

private const val MAX_LOOKUP_IDS = 8
private const val SOURCE_RECCOBEATS = "reccobeats"
private const val SOURCE_DIRECT_RECCOBEATS = "direct_reccobeats"
private const val SOURCE_PROVIDER_ISRC = "provider_isrc_reccobeats"
private const val SOURCE_MUSICBRAINZ_ISRC = "musicbrainz_isrc_reccobeats"
private const val SOURCE_SPOTIFY_FALLBACK = "spotify_fallback_reccobeats"

private val MediaMetadata.matchKey: String
    get() =
        listOf(
            title.matchText(),
            artists.joinToString("|") { it.name.matchText() },
            album?.title.orEmpty().matchText(),
            duration.takeIf { it > 0 }?.toString().orEmpty(),
        ).joinToString("::")

private val RECCO_ID_REGEX = Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""")
private val ISRC_REGEX = Regex("""^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$""")
private val SPOTIFY_ID_REGEX = Regex("^[A-Za-z0-9]{22}$")
private val SPOTIFY_URI_REGEX = Regex("""spotify:track:([A-Za-z0-9]{22})""")
private val SPOTIFY_URL_REGEX = Regex("""open\.spotify\.com/track/([A-Za-z0-9]{22})""")
private val DEEZER_ID_REGEX = Regex("""(?:^deezer:track:|deezer\.com/track/)(\d+)""", RegexOption.IGNORE_CASE)
private val YTM_SUFFIX_REGEX = Regex("""\s+[-–—]\s+YouTube\s+Music$""", RegexOption.IGNORE_CASE)
private val OFFICIAL_TAG_REGEX = Regex("""\s+\((?:official\s+)?(?:audio|video|lyrics?|visualizer|remaster(?:ed)?|explicit)\)""", RegexOption.IGNORE_CASE)
private val FEAT_REGEX = Regex("""\b(feat|ft|featuring)\.?\b""")
private val NON_ALNUM_REGEX = Regex("""[^a-z0-9]+""")

private fun MediaMetadata.directReccoIds(): List<String> =
    listOfNotNull(
        id.isrc(),
        id.spotifyTrackId(),
    )

private fun String.reccoLookupId(): String? =
    isrc()
        ?: spotifyTrackId()
        ?: takeIf { RECCO_ID_REGEX.matches(trim()) }

private fun String.isrc(): String? {
    val normalized =
        trim()
            .uppercase(Locale.US)
            .replace("-", "")
            .replace(" ", "")
    return normalized.takeIf { ISRC_REGEX.matches(it) }
}

private fun String.spotifyTrackId(): String? {
    val trimmed = trim()
    if (SPOTIFY_ID_REGEX.matches(trimmed)) return trimmed
    SPOTIFY_URI_REGEX
        .find(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { return it }
    SPOTIFY_URL_REGEX
        .find(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { return it }
    return null
}

private fun String.deezerTrackId(): String? =
    DEEZER_ID_REGEX
        .find(this)
        ?.groupValues
        ?.getOrNull(1)

private fun String.cleanRecordingQuery(): String =
    replace(YTM_SUFFIX_REGEX, "")
        .replace(OFFICIAL_TAG_REGEX, "")
        .trim()

private fun String.luceneQuoted(): String = replace("\\", " ").replace("\"", " ").trim()

private fun String.matchText(): String =
    cleanRecordingQuery()
        .lowercase(Locale.ROOT)
        .replace("&", "and")
        .replace(FEAT_REGEX, " ")
        .replace(NON_ALNUM_REGEX, " ")
        .trim()

private fun spotifyPitchKey(
    key: Int?,
    mode: Int?,
): String? {
    if (key == null || key !in 0..11) return null
    val names = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    return names[key] + if (mode == 0) "m" else ""
}

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

private fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

private fun JsonObject.double(name: String): Double? = this[name]?.jsonPrimitive?.doubleOrNull

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

private val JsonElement.obj: JsonObject?
    get() = this as? JsonObject
