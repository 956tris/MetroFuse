/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.providers

import com.metrolist.music.apple.AppleMusicCanvasProvider
import com.metrolist.music.deezer.DeezerAudioProvider
import com.metrolist.music.utils.CanvasQueryCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a trusted ISRC for a track from whichever source can supply one,
 * and (when a caller-supplied ISRC is already available) cross-checks it so
 * a bad tag can never silently poison an otherwise-correct match.
 *
 * Resolution order:
 *  1. Caller-supplied candidate ISRC (local tag / MediaStore / cached
 *     metadata / provider field) — normalized + validated shape only.
 *  2. Deezer catalog search by song+artist (cheap, no auth) — first result
 *     with a matching title/artist/duration wins.
 *  3. Apple Music catalog search by song+artist, using the same shared
 *     token as [AppleMusicCanvasProvider].
 *
 * Every lookup is cached in-memory keyed by (song, artist, durationSeconds)
 * so repeat plays of the same track never re-hit the network — this keeps
 * ISRC resolution off the hot path and safe to call from the UI-adjacent
 * playback pipeline.
 */
object IsrcResolver {

    private data class CacheKey(val song: String, val artist: String, val durationSeconds: Int?)

    // Positive + negative results both cached; NEGATIVE_RESULT means
    // "resolution was attempted and failed", distinct from "never attempted"
    // (absent key). ConcurrentHashMap disallows null values at the JVM
    // level (throws NPE from putVal), so a sentinel is used instead of null
    // even though the value type here is nullable.
    private const val NEGATIVE_RESULT = "\u0000NEGATIVE_RESULT\u0000"
    private val cache = ConcurrentHashMap<CacheKey, String>()

    /**
     * Returns a normalized, structurally valid ISRC, or null if none could
     * be resolved/validated. Never throws — network/parse failures degrade
     * to null so callers can fall through to lower-confidence matching.
     */
    suspend fun resolveAndValidate(
        candidateIsrc: String?,
        song: String,
        artist: String,
        durationSeconds: Int?,
    ): String? = withContext(Dispatchers.IO) {
        // 1. Caller-supplied candidate — normalize/validate only, no network.
        ProviderIsrc.normalize(candidateIsrc)?.let { return@withContext it }

        if (song.isBlank() || artist.isBlank()) return@withContext null

        // Clean YTM video-style titles + "- Topic" artists before lookup so
        // frontend tracks without ISRCs resolve to the same catalog entry.
        // Duration bucketed to 15s windows so radio edits/remasters of the
        // same song share a cache entry instead of forking keys.
        val cleanSong = CanvasQueryCleaner.cleanTitle(song).ifBlank { song.trim() }
        val cleanArtist = CanvasQueryCleaner.cleanArtist(artist).ifBlank { artist.trim() }
        if (cleanSong.isBlank() || cleanArtist.isBlank()) return@withContext null
        val durationBucket = durationSeconds?.takeIf { it > 30 }?.let { it / 15 }

        val key = CacheKey(cleanSong.lowercase(), cleanArtist.lowercase(), durationBucket)
        cache[key]?.let { return@withContext if (it == NEGATIVE_RESULT) null else it }

        val resolved = runCatching {
            coroutineScope {
                val deezerDeferred = async { resolveViaDeezer(cleanSong, cleanArtist, durationSeconds) }
                val appleDeferred = async { resolveViaApple(cleanSong, cleanArtist, durationSeconds) }
                deezerDeferred.await() ?: appleDeferred.await()
            }
        }.getOrNull()

        cache[key] = resolved ?: NEGATIVE_RESULT
        resolved
    }

    // Uses DeezerAudioProvider.findBestMatch, which runs the same
    // ISRC-first -> scored title/artist/album/duration search -> song.link
    // fallback chain used for actual playback resolution. This matters:
    // a naive "first search result that happens to carry an ISRC" can
    // easily grab a cover, remix, or same-titled track by a different
    // artist, which then silently poisons the Apple Music canvas match
    // (wrong ISRC -> wrong catalog track -> wrong canvas). Scoring against
    // title + artist + duration before accepting a candidate is what makes
    // this resolution path trustworthy enough to feed into ISRC-keyed
    // lookups elsewhere in the app.
    private suspend fun resolveViaDeezer(song: String, artist: String, durationSeconds: Int?): String? =
        runCatching {
            val query = DeezerAudioProvider.Query(
                mediaId = "",
                title = song,
                artists = listOf(artist),
                album = null,
                isrc = null,
                durationMs = durationSeconds?.toLong()?.times(1000L),
                resolverUrl = DeezerAudioProvider.DEFAULT_RESOLVER_URL,
                quality = com.metrolist.music.constants.DeezerAudioQuality.MP3_128,
                fastMode = false,
                proxyUrl = DeezerAudioProvider.DEFAULT_PROXY_URL,
            )
            DeezerAudioProvider.findBestMatch(query)
                ?.isrc
                ?.let { ProviderIsrc.normalize(it) }
        }.getOrNull()

    private suspend fun resolveViaApple(song: String, artist: String, durationSeconds: Int?): String? =
        runCatching {
            val token = AppleMusicCanvasProvider.borrowToken() ?: return@runCatching null
            AppleMusicCanvasProvider.searchIsrcOnly(song, artist, durationSeconds, token)
        }.getOrNull()

    /** Test/debug hook — clears the in-memory resolution cache. */
    fun clearCache() = cache.clear()
}