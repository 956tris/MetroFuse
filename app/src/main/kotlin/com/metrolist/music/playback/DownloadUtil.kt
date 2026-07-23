/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.metrolist.music.constants.AmazonAudioQuality
import com.metrolist.music.constants.AmazonAudioQualityKey
import com.metrolist.music.constants.ContentCountryKey
import com.metrolist.music.constants.AudioProviderOrder
import com.metrolist.music.constants.AudioProviderOrderItem
import com.metrolist.music.constants.AudioProviderOrderKey
import com.metrolist.music.constants.isPlaybackProvider
import com.metrolist.music.constants.DeezerAudioQuality
import com.metrolist.music.constants.DeezerAudioQualityKey
import com.metrolist.music.constants.DeezerFastModeKey
import com.metrolist.music.constants.DeezerProxyModeKey
import com.metrolist.music.constants.DeezerProxyUrlKey
import com.metrolist.music.constants.DeezerResolverUrlKey
import com.metrolist.music.constants.InstagramCookieKey
import com.metrolist.music.constants.InstagramAppIdKey
import com.metrolist.music.constants.InstagramUserAgentKey
import com.metrolist.music.constants.InstagramUuidKey
import com.metrolist.music.constants.ProxyEnabledKey
import com.metrolist.music.constants.QobuzBackend
import com.metrolist.music.constants.QobuzBackendKey
import com.metrolist.music.constants.QobuzCountryKey
import com.metrolist.music.constants.QobuzCustomInstancesKey
import com.metrolist.music.constants.SoundCloudAuthTokenKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.deezer.DeezerAudioAwareDataSourceFactory
import com.metrolist.music.deezer.DeezerAudioDataSource
import com.metrolist.music.deezer.DeezerAudioProvider
import com.metrolist.music.di.DownloadCache
import com.metrolist.music.di.PlayerCache
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.lyrics.LyricsHelper
import com.metrolist.music.providers.IsrcResolver
import com.metrolist.music.providers.ProviderIsrc
import com.metrolist.music.qobuz.QobuzAudioProvider
import com.metrolist.music.soundcloud.SoundCloudAudioProvider
import com.metrolist.music.instagram.InstagramAudioProvider
import com.metrolist.music.amazon.AmazonAtmosDecryptor
import com.metrolist.music.amazon.AmazonFfmpegDecryptor
import com.metrolist.music.amazon.AmazonAudioProvider
import com.metrolist.music.amazon.AmazonAudioProvider.toAmazonAsinOrNull
import com.metrolist.music.tidal.TidalAudioProvider
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.youtube.YouTubeAudioProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil
@Inject
constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: SimpleCache,
    @PlayerCache val playerCache: SimpleCache,
    private val lyricsHelper: LyricsHelper,
) {
    private val publicExportCleanupIds = ConcurrentHashMap.newKeySet<String>()

    val downloadNotificationHelper = DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

    private data class CachedSongStream(
        val uri: String,
        val expiresAtMs: Long,
        val cacheKey: String,
        val selectionKey: String,
        val kid: String? = null,
        val decryptionKey: String? = null,
    )

    private data class DownloadStreamResolution(
        val uri: String,
        val expiresAtMs: Long,
        val cacheKey: String,
        val format: FormatEntity,
        val kid: String? = null,
        val decryptionKey: String? = null,
    )

    private val songUrlCache = HashMap<String, CachedSongStream>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var cachedInstagramCookie: String = context.dataStore.get(InstagramCookieKey, "")
    @Volatile
    private var cachedInstagramUserAgent: String =
        context.dataStore.get(InstagramUserAgentKey, InstagramAudioProvider.DEFAULT_USER_AGENT)
            .takeIf { it.isNotBlank() }
            ?: InstagramAudioProvider.DEFAULT_USER_AGENT

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    init {
        scope.launch {
            context.dataStore.data
                .map { it[InstagramCookieKey] ?: "" }
                .distinctUntilChanged()
                .collect { cachedInstagramCookie = it }
        }
        scope.launch {
            context.dataStore.data
                .map { prefs ->
                    prefs[InstagramUserAgentKey]
                        ?.takeIf { it.isNotBlank() }
                        ?: InstagramAudioProvider.DEFAULT_USER_AGENT
                }
                .distinctUntilChanged()
                .collect { cachedInstagramUserAgent = it }
        }
    }

    private val dataSourceFactory =
        ResolvingDataSource.Factory(
            DeezerAudioAwareDataSourceFactory(
                normalFactory = CacheDataSource
                        .Factory()
                        .setCache(playerCache)
                        .setUpstreamDataSourceFactory(
                            OkHttpDataSource.Factory(
                                OkHttpClient
                                    .Builder()
                                    .addInterceptor { chain ->
                                        var request = chain.request()
                                        if (request.url.queryParameter(YouTubeAudioProvider.STREAM_MARKER_QUERY) != null) {
                                            val clientName =
                                                request.url.queryParameter(YouTubeAudioProvider.STREAM_MARKER_QUERY)
                                            val cleanUrl =
                                                request.url
                                                    .newBuilder()
                                                    .removeAllQueryParameters(YouTubeAudioProvider.STREAM_MARKER_QUERY)
                                                    .build()
                                            request =
                                                YouTubeAudioProvider.addYouTubePlaybackHeaders(
                                                    request.newBuilder().url(cleanUrl),
                                                    clientName,
                                                    request.header("Range") != null,
                                                ).build()
                                        }
                                        if (request.url.queryParameter(SoundCloudAudioProvider.STREAM_MARKER_QUERY) != null) {
                                            val isApiStream =
                                                request.url.queryParameter(SoundCloudAudioProvider.STREAM_SOURCE_QUERY) ==
                                                        SoundCloudAudioProvider.STREAM_SOURCE_API
                                            val isHlsStream =
                                                request.url.queryParameter(SoundCloudAudioProvider.STREAM_HLS_MARKER_QUERY) == "1"
                                            val cleanUrl =
                                                request.url
                                                    .newBuilder()
                                                    .removeAllQueryParameters(SoundCloudAudioProvider.STREAM_MARKER_QUERY)
                                                    .removeAllQueryParameters(SoundCloudAudioProvider.STREAM_HLS_MARKER_QUERY)
                                                    .removeAllQueryParameters(SoundCloudAudioProvider.STREAM_SOURCE_QUERY)
                                                    .build()
                                            request =
                                                SoundCloudAudioProvider.addPlaybackHeaders(
                                                    request.newBuilder().url(cleanUrl),
                                                    request.header("Range") != null,
                                                    isApiStream,
                                                    isHlsStream,
                                                ).build()
                                        }
                                        if (InstagramAudioProvider.isInstagramPlaybackUrl(request.url)) {
                                            val instagramClient =
                                                InstagramAudioProvider.playbackClientProfile(request.url)
                                            val instagramUserAgent =
                                                InstagramAudioProvider.playbackUserAgent(request.url)
                                                    ?: cachedInstagramUserAgent
                                            val cleanUrl =
                                                InstagramAudioProvider.cleanPlaybackUrl(request.url)
                                            request =
                                                InstagramAudioProvider.addPlaybackHeaders(
                                                    request.newBuilder().url(cleanUrl),
                                                    cachedInstagramCookie,
                                                    request.header("Range") != null,
                                                    instagramClient,
                                                    instagramUserAgent,
                                                ).build()
                                        }
                                        chain.proceed(request)
                                    }.build(),
                            ),
                        ),
                )
        ) { dataSpec ->
            val mediaId =
                dataSpec.key?.let { mediaIdFromDataSpecKey(it) }
                    ?: dataSpec.uri
                        .takeIf { it.scheme.isNullOrBlank() }
                        ?.toString()
                        ?.takeIf { it.isNotBlank() }
                    ?: error("No media id")
            val streamSelectionKey = currentStreamSelectionKey(context)

            if (DeezerAudioDataSource.isDeezerUri(dataSpec.uri)) {
                return@Factory dataSpec
                    .buildUpon()
                    .setKey(deezerFallbackCacheKey(mediaId))
                    .build()
            }

            if (AmazonAudioProvider.isAmazonCdnUrl(dataSpec.uri.toString())) {
                return@Factory dataSpec
                    .buildUpon()
                    .setKey(amazonFallbackCacheKey(mediaId))
                    .build()
            }

            val song = database.getSongByIdBlocking(mediaId)
            if (song?.song?.isLocal == true || song?.song?.isEpisode == true) {
                return@Factory dataSpec
            }

            songUrlCache[mediaId]
                ?.takeIf {
                    it.expiresAtMs > System.currentTimeMillis() &&
                            it.selectionKey == streamSelectionKey
                }
                ?.let { cached ->
                    return@Factory dataSpec
                        .buildUpon()
                        .setUri(cached.uri.toUri())
                        .setKey(cached.cacheKey)
                        .build()
                } ?: run {
                songUrlCache.remove(mediaId)
            }

            val resolved = runBlocking(Dispatchers.IO) {
                resolveDownloadStream(context, mediaId, song)
            }

            database.query {
                upsert(resolved.format)
                getSongByIdBlocking(mediaId)?.song?.let { existing ->
                    upsert(existing.copy(dateDownload = existing.dateDownload ?: LocalDateTime.now()))
                }
            }

            resolved.decryptionKey?.let { key ->
                val asin = mediaId.toAmazonAsinOrNull() ?: AmazonAudioProvider.extractAsinFromKey(resolved.cacheKey)
                if (asin != null) {
                    AmazonAudioProvider.registerDecryptionKey(asin, key)
                }
            }

            songUrlCache[mediaId] = CachedSongStream(
                uri = resolved.uri,
                expiresAtMs = resolved.expiresAtMs,
                cacheKey = resolved.cacheKey,
                selectionKey = streamSelectionKey,
                kid = resolved.kid,
                decryptionKey = resolved.decryptionKey,
            )
            dataSpec
                .buildUpon()
                .setUri(resolved.uri.toUri())
                .setKey(resolved.cacheKey)
                .build()
        }

    private fun currentStreamSelectionKey(context: Context): String {
        val deezerResolverUrl = context.dataStore.get(DeezerResolverUrlKey, DeezerAudioProvider.DEFAULT_RESOLVER_URL)
        val deezerQuality = context.dataStore[DeezerAudioQualityKey].toEnum(DeezerAudioQuality.MP3_128)
        val deezerFastMode = context.dataStore.get(DeezerFastModeKey, false)
        val configuredDeezerProxyUrl = context.dataStore.get(DeezerProxyUrlKey, DeezerAudioProvider.DEFAULT_PROXY_URL)
        val deezerProxyUrl = DeezerAudioProvider.effectiveProxyUrl(
            configuredProxyModeValue = context.dataStore.get(DeezerProxyModeKey, ""),
            configuredProxyUrl = configuredDeezerProxyUrl,
            globalProxyEnabled = context.dataStore.get(ProxyEnabledKey, false),
        )
        val audioProviderOrder = AudioProviderOrder.deserialize(context.dataStore.get(AudioProviderOrderKey, ""))
        val instagramCookie = context.dataStore.get(InstagramCookieKey, "")
        val instagramUserAgent = context.dataStore.get(InstagramUserAgentKey, InstagramAudioProvider.DEFAULT_USER_AGENT)
            .takeIf { it.isNotBlank() }
            ?: InstagramAudioProvider.DEFAULT_USER_AGENT
        val instagramAppId = context.dataStore.get(InstagramAppIdKey, InstagramAudioProvider.DEFAULT_APP_ID)
            .takeIf { it.isNotBlank() }
            ?: InstagramAudioProvider.DEFAULT_APP_ID
        val instagramUuid = context.dataStore.get(InstagramUuidKey, "")
        val instagramCookieConfigured = instagramCookie.isNotBlank()
        val soundCloudAuthConfigured = context.dataStore.get(SoundCloudAuthTokenKey, "").isNotBlank()
        return listOf(
            "deezerResolver=${deezerResolverUrl.hashCode()}",
            "deezerQuality=${deezerQuality.name}",
            "deezerFast=$deezerFastMode",
            "deezerProxy=${DeezerAudioProvider.normalizeProxyUrl(deezerProxyUrl).hashCode()}",
            "providerOrder=${audioProviderOrder.joinToString(",") { it.name }}",
            "instagramAuth=$instagramCookieConfigured",
            "instagramCookie=${instagramCookie.hashCode()}",
            "instagramUserAgent=${instagramUserAgent.hashCode()}",
            "instagramAppId=${instagramAppId.hashCode()}",
            "instagramUuid=${instagramUuid.hashCode()}",
            "soundCloudAuth=$soundCloudAuthConfigured",
        ).joinToString(";")
    }

    private suspend fun resolveDownloadStream(
        context: Context,
        mediaId: String,
        song: Song?,
    ): DownloadStreamResolution {
        val deezerResolverUrl = context.dataStore.get(DeezerResolverUrlKey, DeezerAudioProvider.DEFAULT_RESOLVER_URL)
        val deezerQuality = context.dataStore[DeezerAudioQualityKey].toEnum(DeezerAudioQuality.MP3_128)
        val deezerFastMode = context.dataStore.get(DeezerFastModeKey, false)
        val configuredDeezerProxyUrl = context.dataStore.get(DeezerProxyUrlKey, DeezerAudioProvider.DEFAULT_PROXY_URL)
        val deezerProxyUrl = DeezerAudioProvider.effectiveProxyUrl(
            configuredProxyModeValue = context.dataStore.get(DeezerProxyModeKey, ""),
            configuredProxyUrl = configuredDeezerProxyUrl,
            globalProxyEnabled = context.dataStore.get(ProxyEnabledKey, false),
        )
        val audioProviderOrder = AudioProviderOrder.deserialize(context.dataStore.get(AudioProviderOrderKey, ""))
        val instagramCookie = context.dataStore.get(InstagramCookieKey, "")
        val instagramUserAgent = context.dataStore.get(InstagramUserAgentKey, InstagramAudioProvider.DEFAULT_USER_AGENT)
            .takeIf { it.isNotBlank() }
            ?: InstagramAudioProvider.DEFAULT_USER_AGENT
        val instagramAppId = context.dataStore.get(InstagramAppIdKey, InstagramAudioProvider.DEFAULT_APP_ID)
            .takeIf { it.isNotBlank() }
            ?: InstagramAudioProvider.DEFAULT_APP_ID
        val instagramUuid = context.dataStore.get(InstagramUuidKey, "")
        val soundCloudAuthToken = context.dataStore.get(SoundCloudAuthTokenKey, "")
        val directTidalMediaId = TidalAudioProvider.isTidalTrackId(mediaId)
        val directDeezerMediaId = DeezerAudioProvider.isDeezerTrackId(mediaId)
        val directSoundCloudMediaId = SoundCloudAudioProvider.isSoundCloudUrl(mediaId)

        fun canAttemptOrderedProvider(provider: AudioProviderOrderItem): Boolean =
            if (directTidalMediaId && provider != AudioProviderOrderItem.DEEZER) {
                false
            } else {
                when (provider) {
                    AudioProviderOrderItem.INSTAGRAM -> instagramCookie.isNotBlank()
                    else -> true
                }
            }

        fun DeezerAudioProvider.Resolved.toDownloadResolution(): DownloadStreamResolution =
            DownloadStreamResolution(
                uri = mediaUri,
                expiresAtMs = expiresAtMs,
                cacheKey = deezerFallbackCacheKey(mediaId),
                format = deezerFallbackFormat(mediaId, this),
            )

        fun SoundCloudAudioProvider.Resolved.toDownloadResolution(): DownloadStreamResolution =
            DownloadStreamResolution(
                uri = mediaUri,
                expiresAtMs = expiresAtMs,
                cacheKey = soundCloudFallbackCacheKey(mediaId),
                format = soundCloudFallbackFormat(mediaId, this),
            )

        fun InstagramAudioProvider.Resolved.toDownloadResolution(): DownloadStreamResolution =
            DownloadStreamResolution(
                uri = mediaUri,
                expiresAtMs = expiresAtMs,
                cacheKey = instagramFallbackCacheKey(mediaId),
                format = instagramFallbackFormat(mediaId, this),
            )

        var qobuzAttempt: Result<QobuzAudioProvider.Resolved> =
            Result.failure(IllegalStateException("Qobuz not attempted yet"))
        var soundCloudAttempt: Result<SoundCloudAudioProvider.Resolved> =
            Result.failure(IllegalStateException("SoundCloud not attempted yet"))
        var deezerAttempt: Result<DeezerAudioProvider.Resolved> =
            Result.failure(IllegalStateException("Deezer audio not enabled"))
        var instagramAttempt: Result<InstagramAudioProvider.Resolved> =
            Result.failure(IllegalStateException("Instagram audio not enabled"))
        var amazonAttempt: Result<AmazonAudioProvider.Resolved> =
            Result.failure(IllegalStateException("Amazon Music not enabled"))
        var youtubeAttempt: Result<DownloadStreamResolution> =
            Result.failure(IllegalStateException("YouTube Music not attempted yet"))
        val attemptedProviders = mutableSetOf<AudioProviderOrderItem>()
        val orderedProviders =
            buildList {
                if (directSoundCloudMediaId) add(AudioProviderOrderItem.SOUNDCLOUD)
                if (directTidalMediaId) add(AudioProviderOrderItem.DEEZER)
                if (directDeezerMediaId) add(AudioProviderOrderItem.DEEZER)
                addAll(audioProviderOrder)
            }.distinct()

        suspend fun attemptProvider(provider: AudioProviderOrderItem): DownloadStreamResolution? {
            if (provider in attemptedProviders) return null
            if (!provider.isPlaybackProvider()) return null
            if (!canAttemptOrderedProvider(provider)) return null
            when (provider) {
                AudioProviderOrderItem.SOUNDCLOUD -> {
                    attemptedProviders += provider
                    soundCloudAttempt = runCatching {
                        SoundCloudAudioProvider.resolve(buildSoundCloudQuery(mediaId, song), soundCloudAuthToken)
                    }
                    soundCloudAttempt.getOrNull()?.let { resolved ->
                        Timber.tag(TAG).i("Using SoundCloud stream for download $mediaId: ${resolved.title}")
                        return resolved.toDownloadResolution()
                    }
                }
                AudioProviderOrderItem.TIDAL -> return null
                AudioProviderOrderItem.DEEZER -> {
                    attemptedProviders += provider
                    deezerAttempt = runCatching {
                        DeezerAudioProvider.resolve(
                            buildDeezerQuery(
                                mediaId = mediaId,
                                song = song,
                                resolverUrl = deezerResolverUrl,
                                quality = deezerQuality,
                                fastMode = if (directTidalMediaId) false else deezerFastMode,
                                proxyUrl = deezerProxyUrl,
                            ),
                        )
                    }
                    deezerAttempt.getOrNull()?.let { resolved ->
                        Timber.tag(TAG).i("Using Deezer stream for download $mediaId: ${resolved.label}")
                        return resolved.toDownloadResolution()
                    }
                }
                AudioProviderOrderItem.INSTAGRAM -> {
                    attemptedProviders += provider
                    instagramAttempt = runCatching {
                        InstagramAudioProvider.resolve(
                            buildInstagramQuery(mediaId, song),
                            instagramCookie,
                            instagramUuid,
                            instagramUserAgent,
                            instagramAppId,
                        )
                    }
                    instagramAttempt.getOrNull()?.let { resolved ->
                        Timber.tag(TAG).i("Using Instagram audio stream for download $mediaId: ${resolved.title}")
                        return resolved.toDownloadResolution()
                    }
                }
                AudioProviderOrderItem.AMAZON_MUSIC -> {
                    attemptedProviders += provider
                    amazonAttempt = runCatching {
                        AmazonAudioProvider.resolve(
                            context,
                            buildAmazonQuery(mediaId, song),
                        )
                    }
                    amazonAttempt.getOrNull()?.let { resolved ->
                        Timber.tag(TAG).i("Using Amazon Music stream for download $mediaId: ${resolved.label}")
                        val isAtmos = resolved.codecs.lowercase().contains("eac3")
                        val localPath = if (isAtmos) {
                            AmazonAtmosDecryptor.prepareStream(context, resolved)
                        } else {
                            AmazonFfmpegDecryptor.prepareStream(context, resolved)
                        }

                        val mimeType = if (isAtmos) MimeTypes.AUDIO_MP4 else MimeTypes.AUDIO_FLAC
                        val itag = if (isAtmos) AMAZON_ATMOS_ITAG else AMAZON_FLAC_ITAG

                        return DownloadStreamResolution(
                            uri = android.net.Uri.fromFile(File(localPath)).toString(),
                            expiresAtMs = resolved.expiresAtMs,
                            cacheKey = amazonFallbackCacheKey(mediaId),
                            format = amazonFallbackFormat(mediaId, resolved).copy(
                                itag = itag,
                                mimeType = mimeType,
                            ),
                        )
                    }
                }
                AudioProviderOrderItem.YOUTUBE_MUSIC -> {
                    attemptedProviders += provider
                    youtubeAttempt = runCatching {
                        resolveYouTubeFallback(mediaId)
                    }
                    youtubeAttempt.getOrNull()?.let { resolved ->
                        Timber.tag(TAG).i("Using YouTube Music stream for download $mediaId")
                        return resolved
                    }
                }
                AudioProviderOrderItem.QOBUZ -> {
                    attemptedProviders += provider
                    qobuzAttempt = runCatching {
                        QobuzAudioProvider.resolve(
                            QobuzAudioProvider.Query(
                                mediaId = mediaId,
                                title = song?.song?.title ?: mediaId,
                                artists = song?.orderedArtists?.map { it.name }.orEmpty(),
                                album = song?.song?.albumName ?: song?.album?.title,
                                isrc = ProviderIsrc.firstOf(mediaId, song?.song?.id),
                                durationMs = song?.song?.duration?.toLong()?.times(1000L),
                                countryCode = context.dataStore.get(QobuzCountryKey, "US"),
                                backend = when (context.dataStore[QobuzBackendKey].toEnum(QobuzBackend.KENNY)) {
                                    QobuzBackend.KENNY -> QobuzAudioProvider.ResolverBackend.KENNY
                                },
                                customInstances = context.dataStore.get(QobuzCustomInstancesKey, ""),
                            ),
                        )
                    }
                    qobuzAttempt.getOrNull()?.let { resolved ->
                        Timber.tag(TAG).i("Using Qobuz stream for download $mediaId: ${resolved.label}")
                        return DownloadStreamResolution(
                            uri = resolved.mediaUri,
                            expiresAtMs = resolved.expiresAtMs,
                            cacheKey = qobuzFallbackCacheKey(mediaId),
                            format = qobuzFallbackFormat(mediaId, resolved),
                        )
                    }
                }
            }
            return null
        }

        for (provider in orderedProviders) {
            attemptProvider(provider)?.let { return it }
        }

        if (directTidalMediaId) {
            val deezerError = deezerAttempt.exceptionOrNull()
                ?: IllegalStateException("Deezer audio was not attempted")
            throw DeezerAudioProvider.DeezerResolutionException(
                "Deezer failed: ${deezerError.message ?: deezerError.javaClass.simpleName}",
                deezerError,
            )
        }

        if (!attemptedProviders.contains(AudioProviderOrderItem.SOUNDCLOUD) && !directSoundCloudMediaId) {
            soundCloudAttempt = runCatching {
                SoundCloudAudioProvider.resolve(buildSoundCloudQuery(mediaId, song), soundCloudAuthToken)
            }
            soundCloudAttempt.getOrNull()?.let { resolved ->
                Timber.tag(TAG).i("Using SoundCloud fallback for download $mediaId: ${resolved.title}")
                return resolved.toDownloadResolution()
            }
        }

        if (!attemptedProviders.contains(AudioProviderOrderItem.YOUTUBE_MUSIC)) {
            youtubeAttempt = runCatching {
                resolveYouTubeFallback(mediaId)
            }
        }
        youtubeAttempt.getOrNull()?.let { return it }

        val youtubeError = youtubeAttempt.exceptionOrNull() ?: IllegalStateException("YouTube fallback failed")
        val soundCloudError = soundCloudAttempt.exceptionOrNull() ?: IllegalStateException("SoundCloud fallback failed")
        val deezerDetail = if (attemptedProviders.contains(AudioProviderOrderItem.DEEZER) || directDeezerMediaId) {
            deezerAttempt.exceptionOrNull()?.message
                ?.let { "Deezer failed: $it; " }
                .orEmpty()
        } else {
            ""
        }
        val instagramDetail = if (attemptedProviders.contains(AudioProviderOrderItem.INSTAGRAM)) {
            instagramAttempt.exceptionOrNull()?.message
                ?.let { "Instagram failed: $it; " }
                .orEmpty()
        } else {
            ""
        }
        val amazonDetail = if (attemptedProviders.contains(AudioProviderOrderItem.AMAZON_MUSIC)) {
            amazonAttempt.exceptionOrNull()?.message
                ?.let { "Amazon failed: $it; " }
                .orEmpty()
        } else {
            ""
        }
        val qobuzError = qobuzAttempt.exceptionOrNull() ?: IllegalStateException("Qobuz failed")
        throw QobuzAudioProvider.QobuzResolutionException(
            "Qobuz failed: ${qobuzError.message ?: qobuzError.javaClass.simpleName}; ${deezerDetail}${instagramDetail}${amazonDetail}SoundCloud failed: ${soundCloudError.message ?: soundCloudError.message ?: soundCloudError.javaClass.simpleName}; YouTube failed: ${youtubeError.message ?: youtubeError.javaClass.simpleName}",
            qobuzError,
        )
    }

    private suspend fun resolveYouTubeFallback(mediaId: String): DownloadStreamResolution {
        val resolved = YouTubeAudioProvider.resolve(mediaId)
        return DownloadStreamResolution(
            uri = resolved.mediaUri,
            expiresAtMs = resolved.expiresAtMs,
            cacheKey = youtubeFallbackCacheKey(mediaId),
            format = youtubeFallbackFormat(mediaId, resolved),
        )
    }

    private fun buildSoundCloudQuery(
        mediaId: String,
        song: Song?,
    ): SoundCloudAudioProvider.Query {
        return SoundCloudAudioProvider.Query(
            mediaId = mediaId,
            title = song?.song?.title ?: mediaId,
            artists = song?.orderedArtists?.map { it.name }.orEmpty(),
            album = song?.song?.albumName ?: song?.album?.title,
            durationMs = song?.song?.duration
                ?.takeIf { it > 0 }
                ?.toLong()
                ?.times(1000L),
        )
    }

    private fun buildDeezerQuery(
        mediaId: String,
        song: Song?,
        resolverUrl: String,
        quality: DeezerAudioQuality,
        fastMode: Boolean = false,
        proxyUrl: String = DeezerAudioProvider.DEFAULT_PROXY_URL,
    ): DeezerAudioProvider.Query {
        return DeezerAudioProvider.Query(
            mediaId = mediaId,
            title = song?.song?.title ?: mediaId,
            artists = song?.orderedArtists?.map { it.name }.orEmpty(),
            album = song?.song?.albumName ?: song?.album?.title,
            isrc = ProviderIsrc.firstOf(mediaId, song?.song?.id),
            durationMs = song?.song?.duration
                ?.takeIf { it > 0 }
                ?.toLong()
                ?.times(1000L),
            resolverUrl = resolverUrl,
            quality = quality,
            fastMode = fastMode,
            proxyUrl = proxyUrl,
        )
    }

    private fun buildInstagramQuery(
        mediaId: String,
        song: Song?,
    ): InstagramAudioProvider.Query {
        return InstagramAudioProvider.Query(
            mediaId = mediaId,
            title = song?.song?.title ?: mediaId,
            artists = song?.orderedArtists?.map { it.name }.orEmpty(),
            album = song?.song?.albumName ?: song?.album?.title,
            durationMs = song?.song?.duration
                ?.takeIf { it > 0 }
                ?.toLong()
                ?.times(1000L),
            isrc = ProviderIsrc.firstOf(mediaId, song?.song?.id),
        )
    }

    private fun buildAmazonQuery(
        mediaId: String,
        song: Song?,
    ): AmazonAudioProvider.Query {
        val country = context.dataStore.get(ContentCountryKey, "US")
        val quality = context.dataStore[AmazonAudioQualityKey].toEnum(AmazonAudioQuality.HI_RES).name

        return AmazonAudioProvider.Query(
            mediaId = mediaId,
            title = song?.song?.title ?: mediaId,
            artists = song?.orderedArtists?.map { it.name }.orEmpty(),
            album = song?.song?.albumName ?: song?.album?.title,
            durationMs = song?.song?.duration
                ?.takeIf { it > 0 }
                ?.toLong()
                ?.times(1000L),
            country = country,
            quality = quality,
        )
    }

    @OptIn(DelicateCoroutinesApi::class)
    val downloadManager: DownloadManager =
        DownloadManager(
            context,
            databaseProvider,
            downloadCache,
            dataSourceFactory,
            Executor(Runnable::run)
        ).apply {
            maxParallelDownloads = 3
            addListener(
                object : DownloadManager.Listener {
                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        downloads.update { map ->
                            map.toMutableMap().apply {
                                set(download.request.id, download)
                            }
                        }

                        scope.launch {
                            when (download.state) {
                                Download.STATE_COMPLETED -> {
                                    database.updateDownloadedInfo(download.request.id, true, LocalDateTime.now())
                                    exportAndRemoveInternalDownload(download.request.id, downloadManager)
                                }
                                Download.STATE_FAILED,
                                Download.STATE_STOPPED,
                                Download.STATE_REMOVING -> {
                                    database.updateDownloadedInfo(download.request.id, false, null)
                                }
                                else -> {
                                }
                            }
                        }
                    }

                    override fun onDownloadRemoved(
                        downloadManager: DownloadManager,
                        download: Download,
                    ) {
                        val downloadId = download.request.id
                        val isPublicExportCleanup = publicExportCleanupIds.remove(downloadId)

                        scope.launch {
                            runCatching {
                                if (!isPublicExportCleanup) {
                                    PublicDownloadExporter.deletePublicCopy(context, database, downloadId)
                                }
                                database.updateDownloadedInfo(downloadId, false, null)
                            }.onSuccess {
                                downloads.update { map ->
                                    map.toMutableMap().apply {
                                        remove(downloadId)
                                    }
                                }
                                Timber.tag(TAG).d("Successfully removed download $downloadId from in-memory map")
                            }.onFailure { error ->
                                Timber.tag(TAG).e(error, "Failed to update database for removed download $downloadId, keeping in-memory entry")
                            }
                        }
                    }
                }
            )
        }

    init {
        val result = mutableMapOf<String, Download>()
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            result[cursor.download.request.id] = cursor.download
        }
        downloads.value = result

        val completedDownloads = result.values
            .filter { it.state == Download.STATE_COMPLETED }
            .map { it.request.id }
        if (completedDownloads.isNotEmpty()) {
            scope.launch {
                completedDownloads.forEach { downloadId ->
                    exportAndRemoveInternalDownload(downloadId, downloadManager)
                }
            }
        }
    }

    fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

    fun release() {
        scope.cancel()
    }

    private suspend fun exportAndRemoveInternalDownload(
        downloadId: String,
        downloadManager: DownloadManager,
    ) {
        val exported = PublicDownloadExporter.export(
            context = context,
            database = database,
            downloadCache = downloadCache,
            lyricsHelper = lyricsHelper,
            downloadId = downloadId,
        )
        if (exported) {
            publicExportCleanupIds.add(downloadId)
            runCatching {
                downloadManager.removeDownload(downloadId)
            }.onSuccess {
                Timber.tag(TAG).i("Removed internal download cache after exporting $downloadId")
            }.onFailure { error ->
                publicExportCleanupIds.remove(downloadId)
                Timber.tag(TAG).w(error, "Failed to remove internal download cache for $downloadId")
            }
        }
    }

    private companion object {
        private const val TAG = "DownloadUtil"
        private const val DEEZER_FALLBACK_ITAG = 100_033
        private const val SOUNDCLOUD_FALLBACK_ITAG = 100_031
        private const val INSTAGRAM_FALLBACK_ITAG = 100_041
        private const val AMAZON_FALLBACK_ITAG = 100_045
        private const val AMAZON_FLAC_ITAG = 100_046
        private const val AMAZON_ATMOS_ITAG = 100_047
        private const val DEEZER_FALLBACK_CACHE_PREFIX = "deezer-fallback-audio:"
        private const val SOUNDCLOUD_FALLBACK_CACHE_PREFIX = "soundcloud-fallback-mp3:"
        private const val INSTAGRAM_FALLBACK_CACHE_PREFIX = "instagram-fallback-audio:"
        private const val AMAZON_FALLBACK_CACHE_PREFIX = "amazon-fallback-audio:"
        private const val YOUTUBE_FALLBACK_CACHE_PREFIX = "youtube-fallback-aac:"
        private const val QOBUZ_FALLBACK_CACHE_PREFIX = "qobuz-fallback-v2:"

        private fun deezerFallbackCacheKey(mediaId: String) = "$DEEZER_FALLBACK_CACHE_PREFIX$mediaId"

        private fun soundCloudFallbackCacheKey(mediaId: String) = "$SOUNDCLOUD_FALLBACK_CACHE_PREFIX$mediaId"

        private fun instagramFallbackCacheKey(mediaId: String) = "$INSTAGRAM_FALLBACK_CACHE_PREFIX$mediaId"

        private fun amazonFallbackCacheKey(mediaId: String) = "$AMAZON_FALLBACK_CACHE_PREFIX$mediaId"

        private fun qobuzFallbackCacheKey(mediaId: String) = "$QOBUZ_FALLBACK_CACHE_PREFIX$mediaId"

        private fun youtubeFallbackCacheKey(mediaId: String) = "$YOUTUBE_FALLBACK_CACHE_PREFIX$mediaId"

        private fun mediaIdFromDataSpecKey(key: String) = key
            .removePrefix("qobuz-fallback:")
            .removePrefix("qobuz-fallback-v2:")
            .removePrefix(DEEZER_FALLBACK_CACHE_PREFIX)
            .removePrefix(SOUNDCLOUD_FALLBACK_CACHE_PREFIX)
            .removePrefix(INSTAGRAM_FALLBACK_CACHE_PREFIX)
            .removePrefix(AMAZON_FALLBACK_CACHE_PREFIX)
            .removePrefix(YOUTUBE_FALLBACK_CACHE_PREFIX)

        private fun deezerFallbackFormat(
            mediaId: String,
            resolved: DeezerAudioProvider.Resolved,
        ) = FormatEntity(
            id = mediaId,
            itag = DEEZER_FALLBACK_ITAG,
            mimeType = resolved.mimeType,
            codecs = resolved.codecs,
            bitrate = resolved.bitrate,
            sampleRate = resolved.sampleRate,
            contentLength = resolved.contentLength ?: 0L,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            playbackUrl = null,
        )

        private fun soundCloudFallbackFormat(
            mediaId: String,
            resolved: SoundCloudAudioProvider.Resolved,
        ) = FormatEntity(
            id = mediaId,
            itag = SOUNDCLOUD_FALLBACK_ITAG,
            mimeType = resolved.mimeType,
            codecs = resolved.codecs,
            bitrate = resolved.bitrate,
            sampleRate = resolved.sampleRate,
            contentLength = resolved.contentLength ?: 0L,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            playbackUrl = null,
        )

        private fun instagramFallbackFormat(
            mediaId: String,
            resolved: InstagramAudioProvider.Resolved,
        ) = FormatEntity(
            id = mediaId,
            itag = INSTAGRAM_FALLBACK_ITAG,
            mimeType = resolved.mimeType,
            codecs = resolved.codecs,
            bitrate = resolved.bitrate,
            sampleRate = resolved.sampleRate,
            contentLength = resolved.contentLength ?: 0L,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            playbackUrl = null,
        )

        private fun amazonFallbackFormat(
            mediaId: String,
            resolved: AmazonAudioProvider.Resolved,
        ) = FormatEntity(
            id = mediaId,
            itag = AMAZON_FALLBACK_ITAG,
            mimeType = resolved.mimeType,
            codecs = resolved.codecs,
            bitrate = resolved.bitrate,
            sampleRate = resolved.sampleRate,
            contentLength = 0L,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            playbackUrl = null,
        )

        private fun qobuzFallbackFormat(
            mediaId: String,
            resolved: QobuzAudioProvider.Resolved,
        ) = FormatEntity(
            id = mediaId,
            itag = if (resolved.label.contains("Hi-Res")) 100_028 else 100_027,
            mimeType = resolved.mimeType,
            codecs = resolved.codecs,
            bitrate = resolved.bitrate,
            sampleRate = resolved.sampleRate,
            contentLength = 0L,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            playbackUrl = null,
        )

        private fun youtubeFallbackFormat(
            mediaId: String,
            resolved: YouTubeAudioProvider.Resolved,
        ) = FormatEntity(
            id = mediaId,
            itag = resolved.itag,
            mimeType = resolved.mimeType,
            codecs = resolved.codecs,
            bitrate = resolved.bitrate,
            sampleRate = resolved.sampleRate,
            contentLength = resolved.contentLength ?: 0L,
            loudnessDb = resolved.loudnessDb,
            perceptualLoudnessDb = resolved.perceptualLoudnessDb,
            playbackUrl = null,
        )
    }
}