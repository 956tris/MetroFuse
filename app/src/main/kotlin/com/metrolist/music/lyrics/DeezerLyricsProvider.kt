/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.constants.DeezerCookieKey
import com.metrolist.music.constants.EnableDeezerLyricsKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.deezer.deezerCookieValue
import com.metrolist.music.utils.deezer.isDeezerCookieConfigured
import com.metrolist.music.utils.get
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Deezer lyrics via the Pipe GraphQL API (`pipe.deezer.com/api`,
 * operation `GetLyrics`) — the only in-app source of true word-by-word
 * ("rich sync") timings besides Musixmatch.
 *
 * Auth is login-gated by design: the short-lived Bearer JWT is minted
 * from the user's `arl` cookie via `auth.deezer.com/login/arl` (same
 * cookie already used for Deezer playback), so this provider stays
 * disabled until Deezer auth is configured.
 *
 * Matching: a Deezer frontend id (`deezer:track:<id>`) is used directly;
 * anything else (YTM, Spotify, …) falls back to the public
 * `api.deezer.com/search` endpoint (no auth) with title/artist/duration
 * scoring.
 *
 * Output is the app's `[MM:SS.cc]<MM:SS.cc> word …` rich-sync bracket
 * format, which [LyricsUtils.parseRichSyncLyrics] already renders as
 * karaoke-style word highlighting. Line-synced and plain-text fallbacks
 * degrade gracefully when a track has no word timings.
 */
object DeezerLyricsProvider : LyricsProvider {
    override val name = "Deezer"

    override fun isEnabled(context: Context): Boolean =
        (context.dataStore.get(EnableDeezerLyricsKey, true)) &&
            isDeezerCookieConfigured(context.dataStore.get(DeezerCookieKey, ""))

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> {
        val cookie = context.dataStore.get(DeezerCookieKey, "")
        val arl = deezerCookieValue(cookie, "arl")
        if (arl.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Deezer ARL not configured"))
        }

        val trackId = id.deezerTrackId() ?: searchTrackId(title, artist, duration)
            ?: return Result.failure(NoSuchElementException("No Deezer track found for $title - $artist"))

        // Cached JWT first; a JwtTokenExpiredError mid-flight clears the
        // cache, so the second attempt transparently mints a fresh one.
        val lrc = fetchLyrics(trackId, arl, forceRefreshJwt = false)
            ?: fetchLyrics(trackId, arl, forceRefreshJwt = true)
            ?: return Result.failure(NoSuchElementException("No Deezer lyrics found for $title - $artist"))

        return Result.success(lrc)
    }

    // ---------- JWT (ARL -> short-lived Bearer) ----------

    private const val AUTH_URL = "https://auth.deezer.com/login/arl?jo=p&rto=c&i=c"
    private const val PIPE_URL = "https://pipe.deezer.com/api"
    private const val SEARCH_URL = "https://api.deezer.com/search"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    // JWTs live ~6 minutes; refresh proactively at 5 so playback never races expiry.
    private const val JWT_TTL_MS = 5 * 60 * 1_000L

    @Volatile
    private var cachedJwt: String? = null

    @Volatile
    private var cachedJwtArl: String? = null

    @Volatile
    private var jwtFetchedAtMs: Long = 0L
    private val jwtMutex = Mutex()

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun jwtFor(arl: String, forceRefresh: Boolean): String? {
        if (!forceRefresh) {
            cachedJwt?.takeIf { it.isNotBlank() && cachedJwtArl == arl && System.currentTimeMillis() - jwtFetchedAtMs < JWT_TTL_MS }?.let { return it }
        }
        return jwtMutex.withLock {
            cachedJwt?.takeIf { !forceRefresh && it.isNotBlank() && cachedJwtArl == arl && System.currentTimeMillis() - jwtFetchedAtMs < JWT_TTL_MS }?.let { return@withLock it }
            val fresh = runCatching {
                // NOTE: the auth response is labeled text/plain but the body
                // is JSON — parse the string, don't rely on content type.
                val request = Request.Builder()
                    .url(AUTH_URL)
                    .header("User-Agent", USER_AGENT)
                    .header("Cookie", "arl=$arl")
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    JSONObject(response.body.string()).optString("jwt").takeIf { it.isNotBlank() }
                }
            }.getOrNull()
            if (!fresh.isNullOrBlank()) {
                cachedJwt = fresh
                cachedJwtArl = arl
                jwtFetchedAtMs = System.currentTimeMillis()
            }
            fresh
        }
    }

    // ---------- Track resolution ----------

    private fun String.deezerTrackId(): String? =
        Regex("""(?:^deezer:track:|deezer\.com/track/)(\d+)""", RegexOption.IGNORE_CASE)
            .find(trim())?.groupValues?.getOrNull(1)

    /** Public search fallback for non-Deezer sources (no auth needed). */
    private fun searchTrackId(title: String, artist: String, durationSec: Int): String? =
        runCatching {
            if (title.isBlank() || artist.isBlank()) return@runCatching null
            val query = "track:\"$title\" artist:\"$artist\""
            val url = "$SEARCH_URL?q=${URLEncoder.encode(query, Charsets.UTF_8.name())}&limit=10"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val data = JSONObject(response.body.string()).optJSONArray("data") ?: return@use null
                var bestId: String? = null
                var bestScore = Int.MIN_VALUE
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val itemId = item.opt("id")?.toString()?.takeIf { it.matches(Regex("\\d+")) } ?: continue
                    val score = scoreCandidate(
                        itemTitle = item.optString("title"),
                        itemArtist = item.optJSONObject("artist")?.optString("name").orEmpty(),
                        itemDurationSec = item.optInt("duration", -1),
                        queryTitle = title,
                        queryArtist = artist,
                        queryDurationSec = durationSec,
                    )
                    if (score > bestScore) {
                        bestScore = score
                        bestId = itemId
                    }
                }
                bestId.takeIf { bestScore >= 60 }
            }
        }.getOrNull()

    private fun scoreCandidate(
        itemTitle: String,
        itemArtist: String,
        itemDurationSec: Int,
        queryTitle: String,
        queryArtist: String,
        queryDurationSec: Int,
    ): Int {
        fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        val title = norm(itemTitle)
        val wantTitle = norm(queryTitle)
        val artistName = norm(itemArtist)
        val wantArtist = norm(queryArtist)
        if (title.isBlank() || wantTitle.isBlank()) return Int.MIN_VALUE

        var score = 0
        score += when {
            title == wantTitle -> 100
            title.contains(wantTitle) || wantTitle.contains(title) -> 75
            else -> {
                val words = wantTitle.split(" ").filter { it.length > 3 }
                if (words.isEmpty()) 0 else (words.count { title.contains(it) }.toFloat() / words.size * 55).toInt()
            }
        }
        score += when {
            artistName == wantArtist -> 60
            artistName.contains(wantArtist) || wantArtist.contains(artistName) -> 40
            else -> {
                val words = wantArtist.split(" ").filter { it.length > 3 }
                if (words.isEmpty()) -20 else (words.count { artistName.contains(it) }.toFloat() / words.size * 30).toInt()
            }
        }
        if (queryDurationSec > 30 && itemDurationSec > 30) {
            val diff = kotlin.math.abs(queryDurationSec - itemDurationSec)
            score += when {
                diff < 4 -> 40
                diff < 12 -> 15
                diff > 25 -> -60
                else -> 0
            }
        }
        return score
    }

    // ---------- Pipe GetLyrics ----------

    private suspend fun fetchLyrics(trackId: String, arl: String, forceRefreshJwt: Boolean): String? {
        val jwt = jwtFor(arl, forceRefreshJwt) ?: return null

        val payload = JSONObject()
            .put("operationName", "GetLyrics")
            .put("variables", JSONObject().put("trackId", trackId))
            .put("query", GET_LYRICS_QUERY)
            .toString()
            .toRequestBody("application/json".toMediaType())

        val body = runCatching {
            val request = Request.Builder()
                .url(PIPE_URL)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $jwt")
                .post(payload)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                JSONObject(response.body.string())
            }
        }.getOrNull() ?: return null

        // Expired JWT mid-flight: drop the cache so the caller's retry mints fresh.
        val firstErrorType = body.optJSONArray("errors")?.optJSONObject(0)?.optString("type")
        if (firstErrorType == "JwtTokenExpiredError") {
            cachedJwt = null
            Timber.tag(TAG).d("Deezer JWT expired mid-request for track $trackId")
            return null
        }

        val lyrics = body.optJSONObject("data")?.optJSONObject("track")?.optJSONObject("lyrics") ?: return null
        return wordByWordLrc(lyrics) ?: syncedLinesLrc(lyrics) ?: lyrics.optString("text").takeIf { it.isNotBlank() }
    }

    /** Millis -> MM:SS.cc bracket timestamp (matches the Musixmatch rich-sync shape). */
    private fun Long.toBracketTimestamp(): String {
        val totalCenti = (this / 10).coerceAtLeast(0)
        return "%02d:%02d.%02d".format(totalCenti / 6000, (totalCenti % 6000) / 100, totalCenti % 100)
    }

    private fun wordByWordLrc(lyrics: JSONObject): String? =
        runCatching {
            val lines = lyrics.optJSONArray("synchronizedWordByWordLines") ?: return@runCatching null
            if (lines.length() == 0) return@runCatching null
            buildString {
                for (i in 0 until lines.length()) {
                    val line = lines.optJSONObject(i) ?: continue
                    val words = line.optJSONArray("words") ?: continue
                    if (words.length() == 0) continue
                    append('[').append(line.optLong("start").toBracketTimestamp()).append(']')
                    for (w in 0 until words.length()) {
                        val word = words.optJSONObject(w) ?: continue
                        val text = word.optString("word").takeIf { it.isNotBlank() } ?: continue
                        append('<').append(word.optLong("start").toBracketTimestamp()).append('>').append(text).append(' ')
                    }
                    append('<').append(line.optLong("end").toBracketTimestamp()).append('>').append('\n')
                }
            }.trim().ifBlank { null }
        }.getOrElse { error ->
            Timber.tag(TAG).d(error, "Failed to parse Deezer word-by-word lyrics")
            null
        }

    private fun syncedLinesLrc(lyrics: JSONObject): String? =
        runCatching {
            val lines = lyrics.optJSONArray("synchronizedLines") ?: return@runCatching null
            if (lines.length() == 0) return@runCatching null
            buildString {
                for (i in 0 until lines.length()) {
                    val line = lines.optJSONObject(i) ?: continue
                    val text = line.optString("line").takeIf { it.isNotBlank() } ?: continue
                    append('[').append(line.optLong("milliseconds").toBracketTimestamp()).append(']').append(text).append('\n')
                }
            }.trim().ifBlank { null }
        }.getOrElse { error ->
            Timber.tag(TAG).d(error, "Failed to parse Deezer synced lines")
            null
        }

    private const val TAG = "DeezerLyrics"
    private const val GET_LYRICS_QUERY =
        "query GetLyrics(\$trackId: String!) { track(trackId: \$trackId) { id lyrics { id text " +
            "...SynchronizedWordByWordLines ...SynchronizedLines licence copyright writers __typename } __typename } } " +
            "fragment SynchronizedWordByWordLines on Lyrics { id synchronizedWordByWordLines { start end " +
            "words { start end word __typename } __typename } __typename } " +
            "fragment SynchronizedLines on Lyrics { id synchronizedLines { lrcTimestamp line lineTranslated " +
            "milliseconds duration __typename } __typename }"
}
