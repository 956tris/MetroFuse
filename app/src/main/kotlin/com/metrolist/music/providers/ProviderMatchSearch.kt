/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.providers

import android.content.Context
import com.metrolist.music.apple.AppleMusicSongResolver
import com.metrolist.music.constants.AudioProviderOrder
import com.metrolist.music.constants.AudioProviderOrderItem
import com.metrolist.music.constants.AudioProviderOrderKey
import com.metrolist.music.constants.DeezerAudioQuality
import com.metrolist.music.constants.DeezerAudioQualityKey
import com.metrolist.music.constants.DeezerFastModeKey
import com.metrolist.music.constants.DeezerResolverUrlKey
import com.metrolist.music.constants.QobuzBackend
import com.metrolist.music.constants.QobuzBackendKey
import com.metrolist.music.constants.QobuzCountryKey
import com.metrolist.music.constants.SoundCloudAuthTokenKey
import com.metrolist.music.constants.SpotifyCookieKey
import com.metrolist.music.deezer.DeezerAudioProvider
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.qobuz.QobuzAudioProvider
import com.metrolist.music.soundcloud.SoundCloudAudioProvider
import com.metrolist.music.tidal.TidalAudioProvider
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.spotify.SpotifyCanvasClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object ProviderMatchSearch {
    suspend fun search(
        context: Context,
        metadata: MediaMetadata,
        perProviderLimit: Int = 6,
    ): List<ProviderMatchCandidate> =
        withContext(Dispatchers.IO) {
            val order = AudioProviderOrder.deserialize(context.dataStore.get(AudioProviderOrderKey, ""))
            val candidates = mutableListOf<ProviderMatchCandidate>()
            val spotifyIsrc = resolveSpotifyIsrc(context, metadata)

            order.forEach { provider ->
                runCatching {
                    candidates += searchProviderInternal(context, metadata, provider, perProviderLimit, spotifyIsrc)
                }
            }
            candidates
                .distinctBy { "${it.provider.name}:${it.providerTrackId}" }
        }

    suspend fun searchProvider(
        context: Context,
        metadata: MediaMetadata,
        provider: AudioProviderOrderItem,
        limit: Int = 4,
    ): List<ProviderMatchCandidate> =
        withContext(Dispatchers.IO) {
            searchProviderInternal(
                context = context,
                metadata = metadata,
                provider = provider,
                limit = limit,
                isrcOverride = resolveSpotifyIsrc(context, metadata),
            )
        }

    private fun searchProviderInternal(
        context: Context,
        metadata: MediaMetadata,
        provider: AudioProviderOrderItem,
        limit: Int,
        isrcOverride: String?,
    ): List<ProviderMatchCandidate> =
        when (provider) {
            AudioProviderOrderItem.SOUNDCLOUD -> {
                val term = metadata.searchTerm()
                SoundCloudAudioProvider.searchMetadata(
                    term = term,
                    limit = limit,
                ).map { track ->
                    ProviderMatchCandidate(
                        provider = provider,
                        providerTrackId = track.permalinkUrl,
                        title = track.title,
                        artist = track.artist,
                        album = null,
                        durationMs = track.durationMs,
                        shareUrl = track.permalinkUrl,
                    )
                }
            }
            AudioProviderOrderItem.TIDAL ->
                TidalAudioProvider.searchCandidates(metadata.toTidalQuery(isrcOverride), limit).map { track ->
                    ProviderMatchCandidate(
                        provider = provider,
                        providerTrackId = track.trackId,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        durationMs = track.durationMs,
                        shareUrl = "https://listen.tidal.com/track/${track.trackId}",
                    )
                }
            AudioProviderOrderItem.DEEZER -> {
                val quality = context.dataStore.get(DeezerAudioQualityKey).toEnum(DeezerAudioQuality.MP3_128)
                val resolverUrl = context.dataStore.get(DeezerResolverUrlKey, DeezerAudioProvider.DEFAULT_RESOLVER_URL)
                val fastMode = context.dataStore.get(DeezerFastModeKey, false)
                DeezerAudioProvider.searchCandidates(metadata.toDeezerQuery(resolverUrl, quality, fastMode, isrcOverride), limit).map { track ->
                    ProviderMatchCandidate(
                        provider = provider,
                        providerTrackId = track.trackId,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        durationMs = track.durationMs,
                        shareUrl = "https://www.deezer.com/track/${track.trackId}",
                    )
                }
            }
            AudioProviderOrderItem.YOUTUBE_MUSIC ->
                listOf(
                    ProviderMatchCandidate(
                        provider = provider,
                        providerTrackId = metadata.id,
                        title = metadata.title,
                        artist = metadata.artists.joinToString(", ") { it.name },
                        album = metadata.album?.title,
                        durationMs = metadata.duration.takeIf { it > 0 }?.toLong()?.times(1000L),
                        shareUrl = "https://music.youtube.com/watch?v=${metadata.id}",
                    ),
                )
            AudioProviderOrderItem.QOBUZ -> {
                val backend = context.dataStore.get(QobuzBackendKey).toEnum(QobuzBackend.JUMO)
                val country = context.dataStore.get(QobuzCountryKey, "US")
                QobuzAudioProvider.searchCandidates(
                    query = metadata.toQobuzQuery(country, backend.toProviderBackend(), isrcOverride),
                    limit = limit,
                ).map { track ->
                    ProviderMatchCandidate(
                        provider = provider,
                        providerTrackId = track.trackId,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        durationMs = track.durationMs,
                        shareUrl = "https://open.qobuz.com/track/${track.trackId}",
                    )
                }
            }
            AudioProviderOrderItem.APPLE_MUSIC ->
                AppleMusicSongResolver.searchCandidates(metadata.toAppleQuery(isrcOverride), limit).map { track ->
                    ProviderMatchCandidate(
                        provider = provider,
                        providerTrackId = track.adamId,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        durationMs = track.durationMs,
                        shareUrl = track.url,
                    )
                }
            AudioProviderOrderItem.INSTAGRAM -> emptyList()
        }

    private suspend fun resolveSpotifyIsrc(
        context: Context,
        metadata: MediaMetadata,
    ): String? {
        val trackId =
            metadata.id.takeIf {
                it.startsWith("spotify:track:", ignoreCase = true) ||
                    it.contains("open.spotify.com/track/", ignoreCase = true)
            } ?: return null
        val cookie = context.dataStore.get(SpotifyCookieKey, "").takeIf { it.isNotBlank() } ?: return null
        return SpotifyCanvasClient.resolveTrackIsrc(trackId, cookie)
    }

    private fun MediaMetadata.toTidalQuery(isrcOverride: String? = null): TidalAudioProvider.Query =
        TidalAudioProvider.Query(
            mediaId = id,
            title = title,
            artists = artists.map { it.name },
            album = album?.title,
            isrc = isrcOverride ?: ProviderIsrc.firstOf(id),
            durationMs = duration.takeIf { it > 0 }?.toLong()?.times(1000L),
        )

    private fun MediaMetadata.toDeezerQuery(
        resolverUrl: String,
        quality: DeezerAudioQuality,
        fastMode: Boolean,
        isrcOverride: String? = null,
    ): DeezerAudioProvider.Query =
        DeezerAudioProvider.Query(
            mediaId = id,
            title = title,
            artists = artists.map { it.name },
            album = album?.title,
            isrc = isrcOverride ?: ProviderIsrc.firstOf(id),
            durationMs = duration.takeIf { it > 0 }?.toLong()?.times(1000L),
            resolverUrl = resolverUrl,
            quality = quality,
            fastMode = fastMode,
        )

    private fun MediaMetadata.toQobuzQuery(
        countryCode: String,
        backend: QobuzAudioProvider.ResolverBackend,
        isrcOverride: String? = null,
    ): QobuzAudioProvider.Query =
        QobuzAudioProvider.Query(
            mediaId = id,
            title = title,
            artists = artists.map { it.name },
            album = album?.title,
            isrc = isrcOverride ?: ProviderIsrc.firstOf(id),
            durationMs = duration.takeIf { it > 0 }?.toLong()?.times(1000L),
            countryCode = countryCode
                .trim()
                .uppercase(Locale.US)
                .takeIf { it.matches(Regex("[A-Z]{2}")) }
                ?: "US",
            backend = backend,
        )

    private fun MediaMetadata.toAppleQuery(isrcOverride: String? = null): AppleMusicSongResolver.Query =
        AppleMusicSongResolver.Query(
            mediaId = id,
            title = title,
            artists = artists.map { it.name },
            album = album?.title,
            isrc = isrcOverride ?: ProviderIsrc.firstOf(id),
            durationMs = duration.takeIf { it > 0 }?.toLong()?.times(1000L),
            explicit = explicit,
        )

    private fun MediaMetadata.searchTerm(): String =
        listOf(title, artists.firstOrNull()?.name, album?.title)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")

    private fun QobuzBackend.toProviderBackend(): QobuzAudioProvider.ResolverBackend =
        when (this) {
            QobuzBackend.TRYPT -> QobuzAudioProvider.ResolverBackend.TRYPT
            QobuzBackend.JUMO -> QobuzAudioProvider.ResolverBackend.JUMO
            QobuzBackend.KENNY -> QobuzAudioProvider.ResolverBackend.KENNY
            QobuzBackend.SQUID -> QobuzAudioProvider.ResolverBackend.SQUID
        }
}
