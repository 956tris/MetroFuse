/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.apple

import com.metrolist.music.providers.IsrcDiskStore
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/** How a [CanvasMatchEntry] was matched to its underlying track. */
enum class CanvasMatchTier(val baseConfidence: Int) {
    ISRC_EXACT(100),
    APPLE_CATALOG_ID(90),
    ALBUM_ARTIST_TITLE(75),
    FUZZY(50),
}

/**
 * A single resolved Canvas record. Distinct from the raw AMP response —
 * this is the durable, cacheable unit the rest of the app should key off.
 */
data class CanvasMatchEntry(
    val isrc: String?,
    val appleCatalogId: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    val sourceUrl: String,
    val matchTier: CanvasMatchTier,
    val confidence: Int,
    val lastMatchedAtMs: Long,
)

/**
 * In-memory Canvas index keyed for O(1) ISRC lookup, with a secondary
 * (song, artist) index for the pre-ISRC fallback path. This sits in front
 * of / alongside [AppleMusicCanvasProvider]'s existing cache and gives the
 * rest of the app a single place to ask "do we already know the canvas for
 * this ISRC" without re-deriving cache keys.
 *
 * A lower-confidence entry (e.g. FUZZY) is never allowed to overwrite an
 * existing higher-confidence entry for the same ISRC — see [put].
 */
object CanvasIndex {

    private const val TAG = "CanvasIndex"
    private const val DISK_SECTION = "canvas"
    private val byIsrc = ConcurrentHashMap<String, CanvasMatchEntry>()
    private val bySongArtist = ConcurrentHashMap<String, CanvasMatchEntry>()

    @Volatile
    private var diskLoaded = false

    /** O(1) lookup by normalized ISRC. */
    fun getByIsrc(isrc: String): CanvasMatchEntry? = byIsrc[isrc]

    /** Fallback lookup when no ISRC is available. */
    fun getBySongArtist(song: String, artist: String): CanvasMatchEntry? =
        bySongArtist[songArtistKey(song, artist)]

    /**
     * Stores [entry], refusing to let a lower-confidence match clobber a
     * higher-confidence one already on record for the same ISRC. This is
     * the enforcement point for "never allow a lower-confidence metadata
     * match to override a valid ISRC match".
     */
    fun put(entry: CanvasMatchEntry) {
        if (entry.isrc != null) {
            val existing = byIsrc[entry.isrc]
            if (existing == null || entry.confidence >= existing.confidence) {
                byIsrc[entry.isrc] = entry
            } else {
                return
            }
        } else {
            val key = songArtistKey(entry.title, entry.artist)
            val existing = bySongArtist[key]
            if (existing == null || entry.confidence >= existing.confidence) {
                bySongArtist[key] = entry
            } else {
                return
            }
        }
        persistAsync()
    }

    /** Preloads disk-learned canvas matches. Call once on app start (IO thread). */
    fun preloadFromDisk() {
        synchronized(this) {
            if (diskLoaded) return
            diskLoaded = true
        }
        runCatching {
            val array = IsrcDiskStore.loadSection(DISK_SECTION) ?: return
            var count = 0
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val url = obj.optString("url").takeIf { it.isNotBlank() } ?: continue
                val tier = runCatching { CanvasMatchTier.valueOf(obj.optString("tier")) }.getOrNull()
                    ?: CanvasMatchTier.FUZZY
                val entry = CanvasMatchEntry(
                    isrc = obj.optString("isrc").takeIf { it.isNotBlank() },
                    appleCatalogId = obj.optString("catalogId").takeIf { it.isNotBlank() },
                    title = obj.optString("title"),
                    artist = obj.optString("artist"),
                    album = obj.optString("album").takeIf { it.isNotBlank() },
                    durationMs = obj.opt("durationMs")?.toString()?.toLongOrNull(),
                    sourceUrl = url,
                    matchTier = tier,
                    confidence = obj.optInt("confidence", tier.baseConfidence),
                    lastMatchedAtMs = obj.optLong("matchedAt", 0L),
                )
                // Route through put so confidence rules still apply.
                if (entry.isrc != null) {
                    val existing = byIsrc[entry.isrc]
                    if (existing == null || entry.confidence >= existing.confidence) {
                        byIsrc[entry.isrc] = entry
                        count++
                    }
                } else {
                    val key = songArtistKey(entry.title, entry.artist)
                    val existing = bySongArtist[key]
                    if (existing == null || entry.confidence >= existing.confidence) {
                        bySongArtist[key] = entry
                        count++
                    }
                }
            }
            Timber.tag(TAG).d("Preloaded $count learned canvas matches from disk")
        }.getOrElse { error ->
            Timber.tag(TAG).d(error, "Failed to preload learned canvas matches")
        }
    }

    private fun persistAsync() {
        runCatching {
            val array = JSONArray()
            (byIsrc.values + bySongArtist.values).distinct().forEach { entry ->
                array.put(
                    JSONObject()
                        .put("isrc", entry.isrc.orEmpty())
                        .put("catalogId", entry.appleCatalogId.orEmpty())
                        .put("title", entry.title)
                        .put("artist", entry.artist)
                        .put("album", entry.album.orEmpty())
                        .put("durationMs", entry.durationMs ?: JSONObject.NULL)
                        .put("url", entry.sourceUrl)
                        .put("tier", entry.matchTier.name)
                        .put("confidence", entry.confidence)
                        .put("matchedAt", entry.lastMatchedAtMs),
                )
            }
            IsrcDiskStore.saveSection(DISK_SECTION, array)
        }.onFailure { error ->
            Timber.tag(TAG).d(error, "Failed to persist learned canvas matches")
        }
    }

    fun clear() {
        byIsrc.clear()
        bySongArtist.clear()
    }

    private fun songArtistKey(song: String, artist: String): String =
        "${song.trim().lowercase()}\u001F${artist.trim().lowercase()}"
}
