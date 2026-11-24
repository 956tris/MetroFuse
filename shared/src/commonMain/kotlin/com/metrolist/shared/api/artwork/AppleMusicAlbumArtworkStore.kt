package com.metrolist.shared.api.artwork

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.artworkExplicitness
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.persistentArtworkUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Global Compose-observable store that resolves and caches Apple Music static album artwork
 * overrides for remote thumbnail URLs without blocking initial image rendering.
 */
object AppleMusicAlbumArtworkStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val concurrency = Semaphore(4)
    private val sourceUrlOverrides = mutableStateMapOf<String, String>()
    private val inFlightKeys = mutableSetOf<String>()
    private val completedMissKeys = mutableSetOf<String>()
    private val lock = Any()

    @Volatile
    private var service: AnimatedArtworkService? = null

    fun attach(animatedArtworkService: AnimatedArtworkService) {
        service = animatedArtworkService
    }

    fun overrideFor(url: String?): String? {
        if (!Settings.shared.preferAppleMusicAlbumArtwork || url.isNullOrBlank()) return null
        val key = normalizedSourceKey(url) ?: return null
        return sourceUrlOverrides[key]
    }

    fun cachedFor(
        title: String,
        artist: String,
        album: String? = null,
        explicit: Boolean? = null,
    ): String? {
        if (!Settings.shared.preferAppleMusicAlbumArtwork) return null
        return service?.cachedStaticAlbumArtwork(title, artist, album, explicit)
    }

    fun recordOverride(
        sourceUrl: String?,
        appleArtworkUrl: String?,
    ) {
        val key = normalizedSourceKey(sourceUrl) ?: return
        val appleUrl = appleArtworkUrl?.takeIf { it.isNotBlank() } ?: return
        if (sourceUrlOverrides[key] != appleUrl) {
            sourceUrlOverrides[key] = appleUrl
        }
    }

    fun request(
        sourceUrl: String?,
        title: String?,
        artist: String?,
        album: String? = null,
        explicit: Boolean? = null,
    ) {
        if (!Settings.shared.preferAppleMusicAlbumArtwork) return
        val attached = service ?: return
        val key = normalizedSourceKey(sourceUrl) ?: return
        val normalizedArtist = artist?.trim().orEmpty()
        val normalizedTitle = title?.trim().orEmpty()
        val normalizedAlbum = album?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedArtist.isBlank() || (normalizedTitle.isBlank() && normalizedAlbum == null)) return
        if (sourceUrlOverrides[key] != null) return

        attached.cachedStaticAlbumArtwork(normalizedTitle, normalizedArtist, normalizedAlbum, explicit)?.let { hit ->
            sourceUrlOverrides[key] = hit
            return
        }

        val requestKey = "$key|${Settings.shared.lastResolvedContentCountry}|$normalizedArtist|$normalizedAlbum|$normalizedTitle"
        synchronized(lock) {
            if (requestKey in inFlightKeys || requestKey in completedMissKeys) return
            inFlightKeys.add(requestKey)
        }
        scope.launch {
            try {
                val resolved =
                    concurrency.withPermit {
                        attached.fetchStaticAlbumArtwork(
                            title = normalizedTitle,
                            artist = normalizedArtist,
                            album = normalizedAlbum,
                            explicit = explicit,
                        )
                    }
                if (resolved != null) {
                    sourceUrlOverrides[key] = resolved
                } else {
                    synchronized(lock) {
                        if (completedMissKeys.size > 512) completedMissKeys.clear()
                        completedMissKeys.add(requestKey)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            } finally {
                synchronized(lock) { inFlightKeys.remove(requestKey) }
            }
        }
    }

    internal fun clear() {
        sourceUrlOverrides.clear()
        synchronized(lock) {
            inFlightKeys.clear()
            completedMissKeys.clear()
        }
    }

    private fun normalizedSourceKey(url: String?): String? {
        val candidate = url?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) } ?: return null
        val host =
            candidate
                .substringAfter("://", "")
                .substringBefore('/')
                .substringBefore(':')
                .lowercase()
        if (host == "mzstatic.com" || host.endsWith(".mzstatic.com")) return null
        return persistentArtworkUrl(candidate.substringBefore('#'))
    }
}

@Composable
fun PrefetchAppleMusicAlbumArtwork(item: YTItem) {
    if (!Settings.shared.preferAppleMusicAlbumArtwork) return
    when (item) {
        is SongItem -> {
            if (item.isLocal || item.isUploaded || item.isVideoSong) return
            val artist = item.artists.joinToString(", ") { it.name }
            PrefetchAppleMusicAlbumArtwork(
                key = item.id,
                thumbnailUrl = item.thumbnail,
                title = item.title,
                artist = artist,
                album = item.album?.name,
                explicit = item.artworkExplicitness,
            )
        }

        is AlbumItem -> {
            val artist = item.artists.joinToString(", ") { it.name }
            PrefetchAppleMusicAlbumArtwork(
                key = item.id,
                thumbnailUrl = item.thumbnail,
                title = item.title,
                artist = artist,
                album = item.title,
                explicit = item.isExplicit,
            )
        }

        else -> {
            Unit
        }
    }
}

@Composable
fun PrefetchAppleMusicAlbumArtwork(
    key: Any?,
    thumbnailUrl: String?,
    title: String?,
    artist: String?,
    album: String?,
    explicit: Boolean? = null,
    enabled: Boolean = true,
) {
    if (!enabled || !Settings.shared.preferAppleMusicAlbumArtwork || thumbnailUrl.isNullOrBlank()) return
    LaunchedEffect(key, thumbnailUrl, title, artist, album, explicit) {
        AppleMusicAlbumArtworkStore.request(
            sourceUrl = thumbnailUrl,
            title = title,
            artist = artist,
            album = album,
            explicit = explicit,
        )
    }
}
