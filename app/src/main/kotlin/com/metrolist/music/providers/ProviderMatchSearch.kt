/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.providers

import android.content.Context
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.constants.AmazonAudioQualityKey
import com.metrolist.music.constants.ContentCountryKey
import com.metrolist.music.constants.AudioProviderOrder
import com.metrolist.music.constants.AudioProviderOrderItem
import com.metrolist.music.constants.AudioProviderOrderKey
import com.metrolist.music.constants.DeezerAudioQuality
import com.metrolist.music.constants.DeezerAudioQualityKey
import com.metrolist.music.constants.DeezerFastModeKey
import com.metrolist.music.constants.DeezerProxyModeKey
import com.metrolist.music.constants.DeezerProxyUrlKey
import com.metrolist.music.constants.DeezerResolverUrlKey
import com.metrolist.music.constants.QobuzBackend
import com.metrolist.music.constants.QobuzBackendKey
import com.metrolist.music.constants.QobuzCountryKey
import com.metrolist.music.constants.ProxyEnabledKey
import com.metrolist.music.constants.SoundCloudAuthTokenKey
import com.metrolist.music.constants.SpotifyCookieKey
import com.metrolist.music.constants.TidalResolverEndpointsKey
import com.metrolist.music.constants.QobuzCustomInstancesKey
import com.metrolist.music.constants.isPlaybackProvider
import com.metrolist.music.deezer.DeezerAudioProvider
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.qobuz.QobuzAudioProvider
import com.metrolist.music.amazon.AmazonAudioProvider
import com.metrolist.music.soundcloud.SoundCloudAudioProvider
import com.metrolist.music.tidal.TidalAudioProvider
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.spotify.SpotifyCanvasClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object ProviderMatchSearch {
    private const val SEARCH_TIMEOUT_MS = 8_000L
    private const val CACHE_TTL_MS = 90_000L

    private data class CachedSearch(
        val createdAtMs: Long,
        val candidates: List<ProviderMatchCandidate>,
    )

    private val cache = ConcurrentHashMap<String, CachedSearch>()

    suspend fun search(
        context: Context,
        metadata: MediaMetadata,
        perProviderLimit: Int = 6,
    ): List<ProviderMatchCandidate> =
        withContext(Dispatchers.IO) {
            val order = AudioProviderOrder.deserialize(context.dataStore.get(AudioProviderOrderKey, ""))
            val spotifyIsrc = resolveSpotifyIsrc(context, metadata)
            searchFast(
                context = context,
                metadata = metadata,
                order = order,
                perProviderLimit = perProviderLimit,
                spotifyIsrc = spotifyIsrc,
            )
        }

    private suspend fun searchFast(
        context: Context,
        metadata: MediaMetadata,
        order: List<AudioProviderOrderItem>,
        perProviderLimit: Int,
        spotifyIsrc: String?,
    ): List<ProviderMatchCandidate> {
        val cacheKey = listOf(
            metadata.id,
            metadata.title,
            metadata.artists.joinToString { it.name },
            metadata.album?.title.orEmpty(),
            metadata.duration,
            order.joinToString(),
            perProviderLimit,
        ).joinToString("|")
        val now = System.currentTimeMillis()
        cache[cacheKey]
            ?.takeIf { now - it.createdAtMs < CACHE_TTL_MS }
            ?.let { return it.candidates }

        val candidates = coroutineScope {
            order.map { provider ->
                async(Dispatchers.IO) {
                    withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                        runCatching {
                            searchProviderInternal(context, metadata, provider, perProviderLimit, spotifyIsrc)
                        }.getOrDefault(emptyList())
                    }.orEmpty()
                }
            }.awaitAll().flatten()
        }.distinctBy { "${it.provider.name}:${it.providerTrackId}" }

        cache[cacheKey] = CachedSearch(now, candidates)
        return candidates
    }

    suspend fun searchProvider(
        context: Context,
        metadata: MediaMetadata,
        provider: AudioProviderOrderItem,
        limit: Int = 4,
    ): List<ProviderMatchCandidate> =
        withContext(Dispatchers.IO) {
            if (!provider.isPlaybackProvider()) return@withContext emptyList()
            searchProviderInternal(
                context = context,
                metadata = metadata,
                provider = provider,
                limit = limit,
                isrcOverride = resolveSpotifyIsrc(context, metadata),
            )
        }

    private suspend fun searchProviderInternal(
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
            AudioProviderOrderItem.TIDAL -> {
                val resolverEndpoints = context.dataStore.get(TidalResolverEndpointsKey, "")
                TidalAudioProvider.searchCandidates(
                    query = metadata.toTidalQuery(isrcOverride),
                    limit = limit,
                    resolverEndpoints = resolverEndpoints,
                ).map { track ->
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
            }
            AudioProviderOrderItem.DEEZER -> {
                val quality = context.dataStore.get(DeezerAudioQualityKey).toEnum(DeezerAudioQuality.MP3_128)
                val resolverUrl = context.dataStore.get(DeezerResolverUrlKey, DeezerAudioProvider.DEFAULT_RESOLVER_URL)
                val fastMode = context.dataStore.get(DeezerFastModeKey, false)
                val configuredProxyUrl = context.dataStore.get(DeezerProxyUrlKey, DeezerAudioProvider.DEFAULT_PROXY_URL)
                val proxyUrl = DeezerAudioProvider.effectiveProxyUrl(
                    configuredProxyModeValue = context.dataStore.get(DeezerProxyModeKey, ""),
                    configuredProxyUrl = configuredProxyUrl,
                    globalProxyEnabled = context.dataStore.get(ProxyEnabledKey, false),
                )
                DeezerAudioProvider.searchCandidates(metadata.toDeezerQuery(resolverUrl, quality, fastMode, proxyUrl, isrcOverride), limit).map { track ->
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
                YouTube.search(
                    query = metadata.searchTerm(),
                    filter = YouTube.SearchFilter.FILTER_SONG,
                ).getOrThrow()
                    .items
                    .filterIsInstance<SongItem>()
                    .take(limit)
                    .map { track ->
                        ProviderMatchCandidate(
                            provider = provider,
                            providerTrackId = track.id,
                            title = track.title,
                            artist = track.artists.joinToString(", ") { it.name },
                            album = track.album?.name,
                            durationMs = track.duration?.toLong()?.times(1000L),
                            shareUrl = track.shareLink,
                        )
                    }
            AudioProviderOrderItem.QOBUZ -> {
                val backend = context.dataStore.get(QobuzBackendKey).toEnum<QobuzBackend>(QobuzBackend.KENNY)
                val country = context.dataStore.get(QobuzCountryKey, "US")
                val customInstances = context.dataStore.get(QobuzCustomInstancesKey, "")
                QobuzAudioProvider.searchCandidates(
                    query = metadata.toQobuzQuery(country, backend.toProviderBackend(), isrcOverride, customInstances),
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

            AudioProviderOrderItem.INSTAGRAM -> emptyList()

            AudioProviderOrderItem.AMAZON_MUSIC -> {
                val country = context.dataStore.get(ContentCountryKey, "US")
                val searchTerm = metadata.searchTerm()
                AmazonAudioProvider.searchCandidates(context, searchTerm, country, limit).map { track ->
                    ProviderMatchCandidate(
                        provider = provider,
                        providerTrackId = track.trackId,
                        title = track.title,
                        artist = track.artist,
                        album = track.album,
                        durationMs = track.durationMs,
                        shareUrl = "https://music.amazon.com/tracks/${track.trackId}",
                    )
                }
            }
            AudioProviderOrderItem.APPLE_MUSIC -> emptyList()
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
        proxyUrl: String,
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
            proxyUrl = proxyUrl,
        )

    private fun MediaMetadata.toQobuzQuery(
        countryCode: String,
        backend: QobuzAudioProvider.ResolverBackend,
        isrcOverride: String? = null,
        customInstances: String? = null,
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
            customInstances = customInstances,
        )

    private fun MediaMetadata.searchTerm(): String =
        listOf(title, artists.firstOrNull()?.name, album?.title)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")

    private fun QobuzBackend.toProviderBackend(): QobuzAudioProvider.ResolverBackend =
        when (this) {
            QobuzBackend.KENNY -> QobuzAudioProvider. ResolverBackend.KENNY
        }
}
