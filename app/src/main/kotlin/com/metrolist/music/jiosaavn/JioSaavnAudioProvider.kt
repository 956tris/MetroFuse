/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.jiosaavn

import com.metrolist.music.constants.JioSaavnAudioQuality
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object JioSaavnAudioProvider {
    const val TRACK_ID_PREFIX = "jiosaavn:track:"
    const val PLAYLIST_ID_PREFIX = "jiosaavn:playlist:"
    const val ALBUM_ID_PREFIX = "jiosaavn:album:"
    const val ARTIST_ID_PREFIX = "jiosaavn:artist:"

    private const val TAG = "JioSaavnAudioProvider"
    private const val API_BASE_URL = "https://www.jiosaavn.com/api.php"
    private const val DES_KEY = "38346591"
    // JioSaavn serves its full catalog (including Western labels) to Indian
    // egress only; elsewhere the same queries return knockoffs or nothing.
    // These headers pin us to the Indian catalog on every api.php call. The
    // IPs are generated fresh on each app start inside verified-allocated
    // Indian ranges and stay stable for the whole session (a normal user
    // doesn't change location per request), with failover between ranges.
    private val regionIps: List<String> by lazy {
        val random = java.util.Random()
        listOf(
            "103.57.226.${random.nextInt(254) + 1}",
            "49.248.${random.nextInt(256)}.${random.nextInt(254) + 1}",
            "157.34.${random.nextInt(256)}.${random.nextInt(254) + 1}",
        )
    }
    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val MAX_SEARCH_CANDIDATES = 10
    private const val STREAM_CACHE_TTL_MS = 30 * 60 * 1000L
    private const val STREAM_CACHE_MAX_SIZE = 80
    private const val MIN_ACCEPT_SCORE = 65
    private const val MIN_ACCEPT_SCORE_NO_DURATION = 90
    private const val DURATION_HARD_REJECT_MS = 15_000L

    data class Query(
        val mediaId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
        val isrc: String? = null,
        val quality: JioSaavnAudioQuality = JioSaavnAudioQuality.HIGH,
        val trackIdOverride: String? = null,
        val explicit: Boolean? = null,
    )

    data class Resolved(
        val mediaUri: String,
        val sourceId: String,
        val title: String,
        val artist: String,
        val mimeType: String,
        val codecs: String,
        val bitrate: Int,
        val sampleRate: Int?,
        val contentLength: Long?,
        val expiresAtMs: Long,
    )

    data class TrackCandidate(
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long?,
        val coverUrl: String?,
        val shareUrl: String?,
    )

    data class SongDetails(
        val id: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val albumId: String?,
        val durationMs: Long?,
        val coverUrl: String?,
        val language: String?,
        val year: String?,
        val explicit: Boolean,
        val playCount: Long?,
        val has320: Boolean,
        val encryptedMediaUrl: String?,
        val permaUrl: String?,
    )

    data class CollectionDetails(
        val id: String,
        val title: String,
        val subtitle: String?,
        val image: String?,
        val songs: List<SongDetails>,
    )

    class JioSaavnResolutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private data class CacheEntry(
        val resolved: Resolved,
        val createdAtMs: Long,
    )

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()

    private val streamCache = ConcurrentHashMap<String, CacheEntry>()

    fun isJioSaavnTrackId(mediaId: String): Boolean =
        mediaId.startsWith(TRACK_ID_PREFIX, ignoreCase = true)

    fun trackIdFromMediaId(mediaId: String): String =
        mediaId.removePrefix(TRACK_ID_PREFIX).removePrefix(TRACK_ID_PREFIX.lowercase(Locale.ROOT))

    fun resolve(query: Query): Resolved {
        if (query.title.isBlank()) {
            throw JioSaavnResolutionException("JioSaavn audio needs a track title to search for")
        }
        val cacheKey = listOf(
            query.mediaId.normalizedSearchText(),
            query.title.normalizedSearchText(),
            query.artists.joinToString(",").normalizedSearchText(),
            query.durationMs?.div(1000L)?.toString().orEmpty(),
            query.quality.name,
            query.trackIdOverride.orEmpty(),
        ).joinToString("|")
        getCachedStream(cacheKey)?.let { return it }

        query.trackIdOverride?.takeIf { it.isNotBlank() }?.let { trackId ->
            val details = trackDetails(trackId)
                ?: throw JioSaavnResolutionException("JioSaavn has no track with id $trackId")
            return buildResolved(details, query, cacheKey)
        }

        val candidates = searchTracks(query.title, query.artists)
        if (candidates.isEmpty()) {
            throw JioSaavnResolutionException(
                "JioSaavn search returned nothing for ${query.title} ${query.artists.firstOrNull().orEmpty()}",
            )
        }
        val best = candidates
            .mapNotNull { details ->
                val score = scoreCandidate(details, query) ?: return@mapNotNull null
                details to score
            }
            .maxByOrNull { it.second }
            ?: throw JioSaavnResolutionException(
                "JioSaavn found ${candidates.size} tracks for ${query.title}, " +
                    "but none matched closely enough",
            )
        return buildResolved(best.first, query, cacheKey)
    }

    fun searchCandidates(
        title: String,
        artists: List<String>,
        limit: Int,
    ): List<TrackCandidate> {
        if (title.isBlank()) return emptyList()
        return runCatching {
            searchTracks(title, artists).take(limit).map { details ->
                TrackCandidate(
                    trackId = details.id,
                    title = details.title,
                    artist = details.artists.joinToString(", "),
                    album = details.album,
                    durationMs = details.durationMs,
                    coverUrl = details.coverUrl,
                    shareUrl = details.permaUrl,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun trackDetails(trackId: String): SongDetails? {
        if (trackId.isBlank()) return null
        return runCatching {
            val json = apiGet(
                call = "song.getDetails",
                params = mapOf("pids" to trackId, "cc" to "in"),
            )
            json.optJSONArray("songs")?.asObjectList()
                ?.mapNotNull { parseSongDetail(it) }
                ?.firstOrNull { it.id == trackId }
                ?: json.optJSONArray("songs")?.asObjectList()
                    ?.mapNotNull { parseSongDetail(it) }
                    ?.firstOrNull()
        }.onFailure {
            Timber.tag(TAG).w(it, "JioSaavn track details failed for $trackId")
        }.getOrNull()
    }

    fun playlistDetails(playlistId: String): CollectionDetails? {
        if (playlistId.isBlank()) return null
        return runCatching {
            val json = apiGet(
                call = "playlist.getDetails",
                params = mapOf("listid" to playlistId, "cc" to "in"),
            )
            val songs = json.optJSONArray("list")?.asObjectList()
                ?.mapNotNull { parseSongDetail(it) }
                .orEmpty()
            CollectionDetails(
                id = json.firstString("listid", "id") ?: playlistId,
                title = json.firstString("listname", "title").orEmpty(),
                subtitle = json.firstString("subtitle", "header_desc"),
                image = json.firstString("image"),
                songs = songs,
            )
        }.onFailure {
            Timber.tag(TAG).w(it, "JioSaavn playlist details failed for $playlistId")
        }.getOrNull()
    }

    fun albumDetails(albumId: String): CollectionDetails? {
        if (albumId.isBlank()) return null
        return runCatching {
            val json = apiGet(
                call = "content.getAlbumDetails",
                params = mapOf("albumid" to albumId, "cc" to "in"),
            )
            val songs = json.optJSONArray("songs")?.asObjectList()
                ?.mapNotNull { parseSongDetail(it) }
                .orEmpty()
            CollectionDetails(
                id = json.firstString("albumid", "id") ?: albumId,
                title = json.firstString("title").orEmpty(),
                subtitle = json.firstString("subtitle"),
                image = json.firstString("image"),
                songs = songs,
            )
        }.onFailure {
            Timber.tag(TAG).w(it, "JioSaavn album details failed for $albumId")
        }.getOrNull()
    }

    fun artistTopSongs(artistId: String): CollectionDetails? {
        if (artistId.isBlank()) return null
        return runCatching {
            val json = apiGet(
                call = "artist.getArtistPageDetails",
                params = mapOf("artistId" to artistId, "n_song" to "25", "n_album" to "0", "cc" to "in"),
            )
            val songs = json.optJSONObject("topSongs")?.optJSONArray("songs")?.asObjectList()
                ?.mapNotNull { parseSongDetail(it) }
                .orEmpty()
            CollectionDetails(
                id = json.firstString("artistId", "id") ?: artistId,
                title = json.firstString("name", "title").orEmpty(),
                subtitle = json.firstString("subtitle"),
                image = json.firstString("image"),
                songs = songs,
            )
        }.onFailure {
            Timber.tag(TAG).w(it, "JioSaavn artist details failed for $artistId")
        }.getOrNull()
    }

    fun launchData(language: String): JSONObject? {
        if (language.isBlank()) return null
        return runCatching {
            apiGet(
                call = "webapi.getLaunchData",
                params = emptyMap(),
                cookie = "L=$language;",
                ctx = "wap6dot0",
            )
        }.onFailure {
            Timber.tag(TAG).w(it, "JioSaavn home data failed for language $language")
        }.getOrNull()
    }

    fun searchRaw(
        endpoint: String,
        query: String,
        limit: Int,
    ): JSONObject {
        if (query.isBlank()) return JSONObject()
        return apiGet(
            call = endpoint,
            params = mapOf(
                "q" to query,
                "p" to "1",
                "n" to limit.coerceIn(1, 30).toString(),
            ),
        )
    }

    private fun searchTracks(
        title: String,
        artists: List<String>,
    ): List<SongDetails> {
        // The backend is picky about multi-word queries ("Down Jay Sean" 500s
        // while "Jay Sean Down" works), so fan out across orderings and merge.
        val firstArtist = artists.firstOrNull().orEmpty()
        val queries = linkedSetOf(
            listOf(title, firstArtist).filter { it.isNotBlank() }.joinToString(" "),
            listOf(firstArtist, title).filter { it.isNotBlank() }.joinToString(" "),
            title,
        ).filter { it.isNotBlank() }.take(3)
        if (queries.isEmpty()) return emptyList()

        val found = linkedMapOf<String, SongDetails>()
        queries.forEach { text ->
            runCatching {
                val json = apiGet(
                    call = "search.getResults",
                    params = mapOf("q" to text, "p" to "1", "n" to MAX_SEARCH_CANDIDATES.toString()),
                )
                json.optJSONArray("results")?.asObjectList()
                    ?.mapNotNull { parseSearchSong(it) }
                    .orEmpty()
            }.onSuccess { songs ->
                songs.forEach { found.putIfAbsent(it.id, it) }
            }.onFailure {
                Timber.tag(TAG).w(it, "JioSaavn search failed for query=$text")
            }
        }
        return found.values.toList()
    }

    private fun buildResolved(
        details: SongDetails,
        query: Query,
        cacheKey: String,
    ): Resolved {
        val encrypted = details.encryptedMediaUrl?.takeIf { it.isNotBlank() }
            ?: throw JioSaavnResolutionException("JioSaavn track ${details.id} has no stream URL")
        val base = decryptMediaUrl(encrypted)
            ?: throw JioSaavnResolutionException("JioSaavn track ${details.id} stream URL failed to decode")
        val (url, bitrate) = selectStream(base, query.quality, details.has320)
        return Resolved(
            mediaUri = url,
            sourceId = "jiosaavn_${details.id}_${details.title}_${details.artists.joinToString(",")}".sanitizeId(),
            title = details.title.ifBlank { query.title },
            artist = details.artists.joinToString(", ").ifBlank { query.artists.joinToString(", ") },
            mimeType = mimeTypeForUrl(url),
            codecs = codecsForUrl(url),
            bitrate = bitrate,
            sampleRate = if (bitrate >= 320_000) 48_000 else 44_100,
            contentLength = null,
            expiresAtMs = System.currentTimeMillis() + 12 * 60 * 60 * 1000L,
        ).also { resolved ->
            Timber.tag(TAG).i(
                "Resolved JioSaavn audio for ${query.mediaId}: ${resolved.title} by ${resolved.artist}, " +
                    "bitrate=${resolved.bitrate}",
            )
            cacheStream(cacheKey, resolved)
        }
    }

    private fun selectStream(
        base96Url: String,
        quality: JioSaavnAudioQuality,
        has320: Boolean,
    ): Pair<String, Int> {
        val table = listOf(
            "320" to 320_000,
            "160" to 160_000,
            "96" to 96_000,
            "48" to 48_000,
            "12" to 12_000,
        )
        val startIndex = when (quality) {
            JioSaavnAudioQuality.HIGH -> 0
            JioSaavnAudioQuality.MEDIUM -> 1
            JioSaavnAudioQuality.LOW -> 2
            JioSaavnAudioQuality.MINI -> 3
            JioSaavnAudioQuality.ULTRA_LOW -> 4
        }
        // Walk down from the preferred quality, then back up: always play
        // something rather than nothing, closest bitrate first.
        val order = (startIndex downTo 0).toList() + ((startIndex + 1) until table.size).toList()
        for (index in order) {
            val (suffix, bitrate) = table[index]
            if (suffix == "320" && !has320) continue
            val url = base96Url
                .replace("_96.mp4", "_${suffix}.mp4")
                .replace("_96.mp3", "_${suffix}.mp3")
                .replace("_160.mp4", "_${suffix}.mp4")
                .replace("_160.mp3", "_${suffix}.mp3")
            if (url.startsWith("http", ignoreCase = true)) return url to bitrate
        }
        return base96Url to 96_000
    }

    fun decryptMediaUrl(encryptedUrl: String): String? {
        return runCatching {
            val keySpec = SecretKeySpec(DES_KEY.toByteArray(Charsets.UTF_8), "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec)
            val decoded = android.util.Base64.decode(encryptedUrl.trim(), android.util.Base64.DEFAULT)
            val decrypted = String(cipher.doFinal(decoded), Charsets.UTF_8).trim()
            decrypted.takeIf { it.contains("saavncdn.com", ignoreCase = true) }
        }.onFailure {
            Timber.tag(TAG).w(it, "JioSaavn stream URL decrypt failed")
        }.getOrNull()
    }

    private fun apiGet(
        call: String,
        params: Map<String, String>,
        cookie: String? = null,
        ctx: String = "web6dot0",
        retry: Boolean = true,
    ): JSONObject {
        val url =
            API_BASE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("__call", call)
                .addQueryParameter("_format", "json")
                .addQueryParameter("_marker", "0")
                .addQueryParameter("ctx", ctx)
                .addQueryParameter("api_version", "4")
                .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
                .build()
        var regionIp: String? = regionIps.firstOrNull()
        fun execute(): JSONObject {
            val activeIp = regionIp
            val builder =
                Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", "https://www.jiosaavn.com/")
            if (!cookie.isNullOrBlank()) builder.header("Cookie", cookie)
            if (!activeIp.isNullOrBlank()) {
                builder.header("X-Forwarded-For", activeIp)
                builder.header("X-Real-IP", activeIp)
            }
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) {
                    throw JioSaavnResolutionException(
                        "JioSaavn $call failed with HTTP ${response.code}: ${text.sanitizePreview()}",
                    )
                }
                return JSONObject(text)
            }
        }
        try {
            return execute()
        } catch (throwable: Throwable) {
            // The backend flakes with transient 500s; fail over to the next
            // region IP once before giving up.
            if (!retry) throw throwable
            Timber.tag(TAG).d(throwable, "JioSaavn $call failed, failing over once")
            regionIp = regionIps.getOrNull(1)
            Thread.sleep(800L)
            return execute()
        }
    }

    private fun parseSearchSong(obj: JSONObject): SongDetails? {
        val more = obj.optJSONObject("more_info")
        val artistMap = more?.optJSONObject("artistMap")
        return parseSongFields(
            id = obj.firstString("id"),
            title = obj.firstString("title", "song"),
            subtitle = obj.firstString("subtitle"),
            image = obj.firstString("image"),
            permaUrl = obj.firstString("perma_url"),
            album = more?.firstString("album") ?: obj.firstString("album"),
            albumId = more?.firstString("album_id") ?: obj.firstString("albumid"),
            durationSecs = more?.firstLong("duration") ?: obj.firstLong("duration"),
            language = obj.firstString("language"),
            year = obj.firstString("year"),
            explicit = obj.optString("explicit_content") == "1",
            playCount = obj.firstLong("play_count"),
            artists = artistMap?.optJSONArray("primary_artists")?.artistNames()
                ?: more?.firstString("music")?.split(",")?.map { it.trim() }.orEmpty(),
            encryptedMediaUrl = more?.firstString("encrypted_media_url") ?: obj.firstString("encrypted_media_url"),
            has320 = (more?.optString("320kbps") ?: obj.optString("320kbps")) == "true",
        )
    }

    fun parseSongDetail(obj: JSONObject): SongDetails? {
        val more = obj.optJSONObject("more_info")
        return parseSongFields(
            id = obj.firstString("id"),
            title = obj.firstString("title", "song"),
            subtitle = obj.firstString("subtitle"),
            image = obj.firstString("image"),
            permaUrl = obj.firstString("perma_url"),
            album = obj.firstString("album") ?: more?.firstString("album"),
            albumId = obj.firstString("albumid") ?: more?.firstString("album_id"),
            durationSecs = obj.firstLong("duration") ?: more?.firstLong("duration"),
            language = obj.firstString("language"),
            year = obj.firstString("year"),
            explicit = obj.optString("explicit_content") == "1",
            playCount = obj.firstLong("play_count"),
            artists = obj.firstString("primary_artists")?.split(",")?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: more?.optJSONObject("artistMap")?.optJSONArray("primary_artists")?.artistNames()
                ?: more?.firstString("music")?.split(",")?.map { it.trim() }.orEmpty(),
            encryptedMediaUrl = obj.firstString("encrypted_media_url") ?: more?.firstString("encrypted_media_url"),
            has320 = (obj.optString("320kbps").takeIf { it.isNotBlank() }
                ?: more?.optString("320kbps")).let { it == "true" },
        )
    }

    private fun parseSongFields(
        id: String?,
        title: String?,
        subtitle: String?,
        image: String?,
        permaUrl: String?,
        album: String?,
        albumId: String?,
        durationSecs: Long?,
        language: String?,
        year: String?,
        explicit: Boolean,
        playCount: Long?,
        artists: List<String>,
        encryptedMediaUrl: String?,
        has320: Boolean,
    ): SongDetails? {
        if (id.isNullOrBlank() || title.isNullOrBlank()) return null
        return SongDetails(
            id = id,
            title = decodeHtml(title),
            artists = artists.map { decodeHtml(it) }.filter { it.isNotBlank() },
            album = album?.let { decodeHtml(it) }?.takeIf { it.isNotBlank() },
            albumId = albumId,
            durationMs = durationSecs?.takeIf { it > 0 }?.times(1000L),
            coverUrl = image?.takeIf { it.isNotBlank() }?.replace("150x150", "500x500"),
            language = language,
            year = year,
            explicit = explicit,
            playCount = playCount,
            has320 = has320,
            encryptedMediaUrl = encryptedMediaUrl,
            permaUrl = permaUrl,
        )
    }

    private fun scoreCandidate(
        candidate: SongDetails,
        query: Query,
    ): Int? {
        val normalizedTitle = query.title.normalizedSearchText()
        val normalizedCandidateTitle = candidate.title.normalizedSearchText()
        if (normalizedTitle.isBlank() || normalizedCandidateTitle.isBlank()) return null
        var score = 0
        if (normalizedTitle == normalizedCandidateTitle) score += 100
        if (normalizedCandidateTitle.contains(normalizedTitle) || normalizedTitle.contains(normalizedCandidateTitle)) {
            score += 40
        } else {
            val queryTokens = normalizedTitle.split(' ').filter { it.length > 2 }.toSet()
            val candidateTokens = normalizedCandidateTitle.split(' ').toSet()
            val overlap = queryTokens.intersect(candidateTokens).size
            if (queryTokens.isNotEmpty() && overlap * 2 >= queryTokens.size) score += 15
        }
        val normalizedArtists = candidate.artists.joinToString(" ").normalizedSearchText()
        query.artists.forEach { artist ->
            val normalized = artist.normalizedSearchText()
            if (normalized.isNotBlank() && normalizedArtists.contains(normalized)) score += 35
        }
        val normalizedAlbum = query.album?.normalizedSearchText().orEmpty()
        val normalizedCandidateAlbum = candidate.album?.normalizedSearchText().orEmpty()
        if (normalizedAlbum.isNotBlank() && normalizedCandidateAlbum.isNotBlank() &&
            (normalizedAlbum == normalizedCandidateAlbum || normalizedCandidateAlbum.contains(normalizedAlbum))
        ) {
            score += 10
        }
        val expectedMs = query.durationMs
        val actualMs = candidate.durationMs
        if (expectedMs != null && actualMs != null) {
            val delta = abs(actualMs - expectedMs)
            score +=
                when {
                    delta <= 3_000L -> 50
                    delta <= 8_000L -> 20
                    delta <= DURATION_HARD_REJECT_MS -> 0
                    else -> return null
                }
        }
        // Explicit/clean versions are separate JioSaavn tracks with identical
        // titles and near-identical durations, so text scoring ties and the
        // wrong version wins coin-flips. Swing matches toward the requested
        // version without ever rejecting the only available one.
        query.explicit?.let { wantExplicit ->
            score += if (candidate.explicit == wantExplicit) 25 else -40
        }
        val threshold = if (expectedMs == null) MIN_ACCEPT_SCORE_NO_DURATION else MIN_ACCEPT_SCORE
        return score.takeIf { it >= threshold }
    }

    private fun getCachedStream(key: String): Resolved? {
        val now = System.currentTimeMillis()
        val entry = streamCache[key] ?: return null
        if (now - entry.createdAtMs > STREAM_CACHE_TTL_MS || entry.resolved.expiresAtMs <= now + 20_000L) {
            streamCache.remove(key)
            return null
        }
        return entry.resolved
    }

    private fun cacheStream(
        key: String,
        stream: Resolved,
    ) {
        streamCache[key] = CacheEntry(stream, System.currentTimeMillis())
        if (streamCache.size > STREAM_CACHE_MAX_SIZE) {
            repeat((streamCache.size - STREAM_CACHE_MAX_SIZE).coerceAtLeast(1)) {
                val oldest = streamCache.entries.minByOrNull { it.value.createdAtMs } ?: return@repeat
                streamCache.remove(oldest.key)
            }
        }
    }

    private fun JSONArray.asObjectList(): List<JSONObject> {
        val list = mutableListOf<JSONObject>()
        for (index in 0 until length()) {
            optJSONObject(index)?.let { list += it }
        }
        return list
    }

    private fun JSONArray.artistNames(): List<String> {
        val names = mutableListOf<String>()
        for (index in 0 until length()) {
            optJSONObject(index)?.firstString("name")?.takeIf { it.isNotBlank() }?.let { names += it }
        }
        return names
    }

    private fun JSONObject.firstString(vararg keys: String): String? {
        keys.forEach { key ->
            when (val value = opt(key)) {
                is String -> if (value.isNotBlank()) return value.trim()
                is Number -> return value.toString()
                else -> Unit
            }
        }
        return null
    }

    private fun JSONObject.firstLong(vararg keys: String): Long? {
        keys.forEach { key ->
            when (val value = opt(key)) {
                is Number -> return value.toLong()
                is String -> value.toLongOrNull()?.let { return it }
                else -> Unit
            }
        }
        return null
    }

    private fun mimeTypeForUrl(url: String): String =
        when {
            url.contains(".mp3", ignoreCase = true) -> "audio/mpeg"
            else -> "audio/mp4"
        }

    private fun codecsForUrl(url: String): String =
        when {
            url.contains(".mp3", ignoreCase = true) -> "mp3"
            else -> "mp4a.40.2"
        }

    private fun decodeHtml(text: String): String =
        text
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")

    private fun String.normalizedSearchText(): String {
        val normalized =
            Normalizer
                .normalize(this, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
        return normalized
            .lowercase(Locale.ROOT)
            .replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()
    }

    private fun String.sanitizeId(): String =
        lowercase(Locale.ROOT)
            .replace(Regex("""[^a-z0-9._-]+"""), "_")
            .trim('_')
            .take(120)
            .ifBlank { UUID.randomUUID().toString() }

    private fun String.sanitizePreview(max: Int = 300): String =
        replace(Regex("\\s+"), " ")
            .trim()
            .let { if (it.length > max) it.take(max) else it }
}
