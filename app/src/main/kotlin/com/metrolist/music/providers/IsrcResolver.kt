/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.providers

import android.content.Context
import com.metrolist.music.apple.AppleMusicCanvasProvider
import com.metrolist.music.deezer.DeezerAudioProvider
import com.metrolist.music.utils.CanvasQueryCleaner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a trusted ISRC for a track from whichever source can supply one,
 * and (when a caller-supplied ISRC is already available) cross-checks it so
 * a bad tag can never silently poison an otherwise-correct match.
 *
 * Resolution order:
 *  1. Caller-supplied candidate ISRC (local tag / MediaStore / cached
 *     metadata / provider field) — normalized + validated shape only.
 *  2. Harvested ISRCs previously learned by audio/canvas providers during
 *     real playback (see [publish]) — exact duration bucket first, then a
 *     duration-agnostic song+artist fallback.
 *  3. Deezer catalog search by song+artist (cheap, no auth) — first result
 *     with a matching title/artist/duration wins.
 *  4. Apple Music catalog search by song+artist, using the same shared
 *     token as [AppleMusicCanvasProvider].
 *
 * Every lookup is cached in-memory keyed by (song, artist, durationSeconds)
 * so repeat plays of the same track never re-hit the network — this keeps
 * ISRC resolution off the hot path and safe to call from the UI-adjacent
 * playback pipeline. Positive results are additionally persisted to disk
 * (see [IsrcDiskStore]) so learning survives process restarts.
 */
object IsrcResolver {

    private data class CacheKey(val song: String, val artist: String, val durationSeconds: Int?)

    // Positive + negative results both cached; NEGATIVE_RESULT means
    // "resolution was attempted and failed", distinct from "never attempted"
    // (absent key). ConcurrentHashMap disallows null values at the JVM
    // level (throws NPE from putVal), so a sentinel is used instead of null
    // even though the value type here is nullable.
    private const val NEGATIVE_RESULT = "\u0000NEGATIVE_RESULT\u0000"
    private const val TAG = "IsrcResolver"
    private const val DISK_SECTION = "isrc"
    private val cache = ConcurrentHashMap<CacheKey, String>()

    /**
     * Duration-agnostic fallback: (clean song, clean artist) -> ISRC.
     * Harvested entries often lack a trustworthy duration (or were learned
     * under a different duration bucket than the current lookup), so the
     * exact-bucket [cache] alone would miss them. Exact-bucket hits always
     * win; this is only consulted on a bucket miss.
     */
    private val unversioned = ConcurrentHashMap<Pair<String, String>, String>()

    @Volatile
    private var diskLoaded = false
    private val diskLock = Any()

    /** Call once from `Application.onCreate` — preloads disk-learned ISRCs. */
    fun init(context: Context) {
        IsrcDiskStore.init(context)
        synchronized(diskLock) {
            if (diskLoaded) return
            diskLoaded = true
        }
        runCatching {
            val array = IsrcDiskStore.loadSection(DISK_SECTION) ?: return
            for (i in 0 until array.length()) {
                val entry = array.optJSONObject(i) ?: continue
                val song = entry.optString("s").takeIf { it.isNotBlank() } ?: continue
                val artist = entry.optString("a").takeIf { it.isNotBlank() } ?: continue
                val isrc = ProviderIsrc.normalize(entry.optString("isrc")) ?: continue
                val bucket = entry.opt("b")?.toString()?.toIntOrNull()
                cache[CacheKey(song, artist, bucket)] = isrc
                unversioned[song to artist] = isrc
            }
            Timber.tag(TAG).d("Preloaded ${array.length()} learned ISRCs from disk")
        }.getOrElse { error ->
            Timber.tag(TAG).d(error, "Failed to preload learned ISRCs")
        }
    }

    /**
     * Harvests an ISRC learned elsewhere (audio provider playback
     * resolution, canvas matching, downloads). Only accepts structurally
     * valid ISRCs; only stores when song+artist are non-blank after
     * cleaning. Never throws — safe to call from any provider path.
     *
     * Both the caller's exact duration bucket and the duration-agnostic
     * fallback are written so later lookups hit regardless of which
     * duration variant they carry.
     */
    fun publish(
        song: String,
        artist: String,
        isrc: String?,
        durationSeconds: Int?,
    ) {
        val normalized = ProviderIsrc.normalize(isrc) ?: return
        val clean = cleanPair(song, artist) ?: return
        val (cleanSong, cleanArtist) = clean
        val bucket = durationBucket(durationSeconds)
        cache[CacheKey(cleanSong, cleanArtist, bucket)] = normalized
        unversioned[cleanSong to cleanArtist] = normalized
        persistAsync()
    }

    /**
     * Synchronous cache-only lookup: caller tags, harvested entries and
     * disk-learned ISRCs — never hits the network. Used by the canvas fast
     * path to decide whether the precise ISRC-first route is available
     * without paying discovery cost.
     */
    fun peek(
        song: String,
        artist: String,
        durationSeconds: Int?,
    ): String? {
        val clean = cleanPair(song, artist) ?: return null
        val (cleanSong, cleanArtist) = clean
        val key = CacheKey(cleanSong, cleanArtist, durationBucket(durationSeconds))
        cache[key]?.takeIf { it != NEGATIVE_RESULT }?.let { return it }
        return unversioned[cleanSong to cleanArtist]
    }

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
        val clean = cleanPair(song, artist) ?: return@withContext null
        val (cleanSong, cleanArtist) = clean
        val durationBucket = durationBucket(durationSeconds)

        val key = CacheKey(cleanSong, cleanArtist, durationBucket)
        cache[key]?.let { return@withContext if (it == NEGATIVE_RESULT) null else it }

        // 2. Harvested / disk-learned ISRC under a different duration bucket.
        unversioned[cleanSong to cleanArtist]?.let { harvested ->
            cache[key] = harvested
            return@withContext harvested
        }

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
    // ISRC-first -> scored title/artist/album/duration search chain used
    // for actual playback resolution. This matters:
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

    private fun cleanPair(song: String, artist: String): Pair<String, String>? {
        val cleanSong = CanvasQueryCleaner.cleanTitle(song).ifBlank { song.trim() }.lowercase()
        val cleanArtist = CanvasQueryCleaner.cleanArtist(artist).ifBlank { artist.trim() }.lowercase()
        if (cleanSong.isBlank() || cleanArtist.isBlank()) return null
        return cleanSong to cleanArtist
    }

    private fun durationBucket(durationSeconds: Int?): Int? =
        durationSeconds?.takeIf { it > 30 }?.let { it / 15 }

    private fun persistAsync() {
        runCatching {
            val array = org.json.JSONArray()
            // Persist positives only; negatives are cheap to recompute and
            // may go stale as catalogs gain canvases.
            cache.entries
                .filter { it.value != NEGATIVE_RESULT }
                .sortedByDescending { it.key.durationSeconds ?: -1 }
                .forEach { (key, isrc) ->
                    array.put(
                        JSONObject()
                            .put("s", key.song)
                            .put("a", key.artist)
                            .put("b", key.durationSeconds ?: JSONObject.NULL)
                            .put("isrc", isrc),
                    )
                }
            IsrcDiskStore.saveSection(DISK_SECTION, array)
        }.onFailure { error ->
            Timber.tag(TAG).d(error, "Failed to persist learned ISRCs")
        }
    }

    /** Test/debug hook — clears the in-memory resolution cache. */
    fun clearCache() {
        cache.clear()
        unversioned.clear()
    }
}
