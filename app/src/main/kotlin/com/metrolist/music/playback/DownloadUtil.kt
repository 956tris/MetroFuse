/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import com.metrolist.music.apple.AppleMusicAwareDataSourceFactory
import com.metrolist.music.apple.AppleMusicSongResolver
import com.metrolist.music.apple.AppleMusicWrapperDataSource
import com.metrolist.music.apple.AppleMusicWrapperManagerProvider
import com.metrolist.music.constants.AppleMusicAudioQuality
import com.metrolist.music.constants.AppleMusicAudioQualityKey
import com.metrolist.music.constants.AppleMusicFallbackEnabledKey
import com.metrolist.music.constants.AppleMusicWrapperHostKey
import com.metrolist.music.constants.AppleMusicWrapperSecureKey
import com.metrolist.music.constants.AudioProviderOrder
import com.metrolist.music.constants.AudioProviderOrderItem
import com.metrolist.music.constants.AudioProviderOrderKey
import com.metrolist.music.constants.AppleMusicForceAlacKey
import com.metrolist.music.constants.AppleMusicSuperFastKey
import com.metrolist.music.constants.DeezerAudioQuality
import com.metrolist.music.constants.DeezerAudioQualityKey
import com.metrolist.music.constants.DeezerFastModeKey
import com.metrolist.music.constants.DeezerResolverUrlKey
import com.metrolist.music.constants.InstagramCookieKey
import com.metrolist.music.constants.InstagramAppIdKey
import com.metrolist.music.constants.InstagramUserAgentKey
import com.metrolist.music.constants.InstagramUuidKey
import com.metrolist.music.constants.QobuzBackend
import com.metrolist.music.constants.QobuzBackendKey
import com.metrolist.music.constants.QobuzCountryKey
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
import com.metrolist.music.providers.ProviderIsrc
import com.metrolist.music.qobuz.QobuzAudioProvider
import com.metrolist.music.soundcloud.SoundCloudAudioProvider
import com.metrolist.music.instagram.InstagramAudioProvider
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
import java.time.LocalDateTime
import java.util.Locale
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
    private val TAG = "DownloadUtil"
    private val publicExportCleanupIds = ConcurrentHashMap.newKeySet<String>()

    private data class CachedSongStream(
        val uri: String,
        val expiresAtMs: Long,
        val cacheKey: String,
        val selectionKey: String,
    )

    private data class DownloadStreamResolution(
        val uri: String,
        val expiresAtMs: Long,
        val cacheKey: String,
        val format: FormatEntity,
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
                AppleMusicAwareDataSourceFactory(
                    CacheDataSource
                        .Factory()
                        .setCache(playerCache)
                        .setUpstreamDataSourceFactory(
                            OkHttpDataSource.Factory(
                                OkHttpClient
                                    .Builder()
                                    .addInterceptor { chain ->
                                    var request = chain.request()
                                    if (request.url.queryParameter(YouTubeAudioProvider.STREAM_MARKER_QUERY) != null) {
                                        val clientName = request.url.queryParameter(YouTubeAudioProvider.STREAM_MARKER_QUERY)
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
                                        val cleanUrl = InstagramAudioProvider.cleanPlaybackUrl(request.url)
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
                ),
            ),
        ) { dataSpec ->
            val mediaId =
                dataSpec.key?.let(::mediaIdFromDataSpecKey)
                    ?: dataSpec.uri
                        .takeIf { it.scheme.isNullOrBlank() }
                        ?.toString()
                        ?.takeIf { it.isNotBlank() }
                    ?: error("No media id")
            val streamSelectionKey = currentStreamSelectionKey(context)

            if (AppleMusicWrapperDataSource.isAppleUri(dataSpec.uri)) {
                return@Factory dataSpec
                    .buildUpon()
                    .setKey(appleWrapperCacheKey(mediaId))
                    .build()
            }
            if (DeezerAudioDataSource.isDeezerUri(dataSpec.uri)) {
                return@Factory dataSpec
                    .buildUpon()
                    .setKey(deezerFallbackCacheKey(mediaId))
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

            songUrlCache[mediaId] = CachedSongStream(
                uri = resolved.uri,
                expiresAtMs = resolved.expiresAtMs,
                cacheKey = resolved.cacheKey,
                selectionKey = streamSelectionKey,
            )
            dataSpec
                .buildUpon()
                .setUri(resolved.uri.toUri())
                .setKey(resolved.cacheKey)
                .build()
        }

    private fun currentStreamSelectionKey(context: Context): String {
        val appleMusicFallbackEnabled = context.dataStore.get(AppleMusicFallbackEnabledKey, true)
        val appleMusicForceAlac = context.dataStore.get(AppleMusicForceAlacKey, false)
        val appleMusicAudioMode = context.currentAppleMusicWrapperMode()
        val appleMusicSuperFast = context.dataStore.get(AppleMusicSuperFastKey, false)
        val appleWrapperHost = context.dataStore.get(AppleMusicWrapperHostKey, AppleMusicWrapperManagerProvider.DEFAULT_HOST)
        val appleWrapperSecure = context.dataStore.get(AppleMusicWrapperSecureKey, true)
        val deezerResolverUrl = context.dataStore.get(DeezerResolverUrlKey, DeezerAudioProvider.DEFAULT_RESOLVER_URL)
        val deezerQuality = context.dataStore.get(DeezerAudioQualityKey).toEnum(DeezerAudioQuality.MP3_128)
        val deezerFastMode = context.dataStore.get(DeezerFastModeKey, false)
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
        val qobuzBackend = context.dataStore.get(QobuzBackendKey).toEnum(QobuzBackend.JUMO)
        val qobuzCountry = context.dataStore.get(QobuzCountryKey, "US")
            .trim()
            .uppercase(Locale.US)
            .takeIf { it.matches(Regex("[A-Z]{2}")) }
            ?: "US"
        return listOf(
            "appleFallback=$appleMusicFallbackEnabled",
            "appleForceAlac=$appleMusicForceAlac",
            "appleAudioMode=${appleMusicAudioMode.name}",
            "appleSuperFast=$appleMusicSuperFast",
            "appleWrapperHost=${appleWrapperHost.hashCode()}",
            "appleWrapperSecure=$appleWrapperSecure",
            "deezerResolver=${deezerResolverUrl.hashCode()}",
            "deezerQuality=${deezerQuality.name}",
            "deezerFast=$deezerFastMode",
            "providerOrder=${audioProviderOrder.joinToString(",") { it.name }}",
            "instagramAuth=$instagramCookieConfigured",
            "instagramCookie=${instagramCookie.hashCode()}",
            "instagramUserAgent=${instagramUserAgent.hashCode()}",
            "instagramAppId=${instagramAppId.hashCode()}",
            "instagramUuid=${instagramUuid.hashCode()}",
            "soundCloudAuth=$soundCloudAuthConfigured",
            "backend=${qobuzBackend.name}",
            "country=$qobuzCountry",
        ).joinToString(";")
    }

    private suspend fun resolveDownloadStream(
        context: Context,
        mediaId: String,
        song: Song?,
    ): DownloadStreamResolution {
        val appleMusicFallbackEnabled = context.dataStore.get(AppleMusicFallbackEnabledKey, true)
        val appleMusicForceAlac = context.dataStore.get(AppleMusicForceAlacKey, false)
        val appleMusicAudioMode = context.currentAppleMusicWrapperMode()
        val appleMusicSuperFast = context.dataStore.get(AppleMusicSuperFastKey, false)
        val appleWrapperHost = context.dataStore.get(AppleMusicWrapperHostKey, AppleMusicWrapperManagerProvider.DEFAULT_HOST)
        val appleWrapperSecure = context.dataStore.get(AppleMusicWrapperSecureKey, true)
        val deezerResolverUrl = context.dataStore.get(DeezerResolverUrlKey, DeezerAudioProvider.DEFAULT_RESOLVER_URL)
        val deezerQuality = context.dataStore.get(DeezerAudioQualityKey).toEnum(DeezerAudioQuality.MP3_128)
        val deezerFastMode = context.dataStore.get(DeezerFastModeKey, false)
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
        val directDeezerMediaId = DeezerAudioProvider.isDeezerTrackId(mediaId)
        val directSoundCloudMediaId = SoundCloudAudioProvider.isSoundCloudUrl(mediaId)

        fun canAttemptOrderedProvider(provider: AudioProviderOrderItem): Boolean =
            when (provider) {
                AudioProviderOrderItem.APPLE_MUSIC -> appleMusicFallbackEnabled || appleMusicForceAlac
                AudioProviderOrderItem.INSTAGRAM -> instagramCookie.isNotBlank()
                else -> true
            }

        fun AppleMusicSongResolver.Resolved.toDownloadResolution(): DownloadStreamResolution =
            DownloadStreamResolution(
                uri = AppleMusicWrapperDataSource.toProgressiveStreamUri(
                    uri = mediaUri.toUri(),
                    highWorkerMode = appleMusicSuperFast,
                ).toString(),
                expiresAtMs = expiresAtMs,
                cacheKey = appleWrapperCacheKey(mediaId),
                format = appleWrapperFormat(mediaId, bitrate = bitrate, sampleRate = sampleRate, audioMode = audioMode),
            )

        fun QobuzAudioProvider.Resolved.toDownloadResolution(): DownloadStreamResolution =
            DownloadStreamResolution(
                uri = mediaUri,
                expiresAtMs = expiresAtMs,
                cacheKey = qobuzFallbackCacheKey(mediaId),
                format = qobuzFallbackFormat(mediaId, this),
            )

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

        var appleAttempt: Result<AppleMusicSongResolver.Resolved> =
            if (appleMusicForceAlac) {
                Result.failure(IllegalStateException("Apple Music ALAC not attempted yet"))
            } else if (appleMusicFallbackEnabled) {
                Result.failure(IllegalStateException("Apple Music not attempted yet"))
            } else {
                Result.failure(IllegalStateException("Apple Music fallback disabled"))
            }
        var soundCloudAttempt: Result<SoundCloudAudioProvider.Resolved> =
            Result.failure(IllegalStateException("SoundCloud not attempted yet"))
        var deezerAttempt: Result<DeezerAudioProvider.Resolved> =
            Result.failure(IllegalStateException("Deezer audio not enabled"))
        var instagramAttempt: Result<InstagramAudioProvider.Resolved> =
            Result.failure(IllegalStateException("Instagram audio not enabled"))
        var youtubeAttempt: Result<DownloadStreamResolution> =
            Result.failure(IllegalStateException("YouTube Music not attempted yet"))
        var qobuzAttempt: Result<QobuzAudioProvider.Resolved> =
            Result.failure(IllegalStateException("Qobuz not attempted yet"))
        val attemptedProviders = mutableSetOf<AudioProviderOrderItem>()
        val orderedProviders =
            buildList {
                if (directSoundCloudMediaId) add(AudioProviderOrderItem.SOUNDCLOUD)
                if (directDeezerMediaId) add(AudioProviderOrderItem.DEEZER)
                addAll(audioProviderOrder)
            }.distinct()

        suspend fun attemptProvider(provider: AudioProviderOrderItem): DownloadStreamResolution? {
            if (provider in attemptedProviders) return null
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
                                fastMode = deezerFastMode,
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
                        QobuzAudioProvider.resolve(buildQobuzQuery(context, mediaId, song))
                    }
                    qobuzAttempt.getOrNull()?.let { resolved ->
                        return resolved.toDownloadResolution()
                    }
                }
                AudioProviderOrderItem.APPLE_MUSIC -> {
                    attemptedProviders += provider
                    appleAttempt = runCatching {
                        AppleMusicSongResolver.resolve(
                            buildAppleMusicQuery(
                                mediaId = mediaId,
                                song = song,
                                wrapperHost = appleWrapperHost,
                                wrapperSecure = appleWrapperSecure,
                                audioMode = appleMusicAudioMode,
                                highWorkerMode = appleMusicSuperFast,
                            ),
                        )
                    }
                    appleAttempt.getOrNull()?.let { resolved ->
                        return resolved.toDownloadResolution()
                    }
                }
            }
            return null
        }

        for (provider in orderedProviders) {
            attemptProvider(provider)?.let { return it }
            if (appleMusicForceAlac && provider == AudioProviderOrderItem.APPLE_MUSIC) {
                val appleError = appleAttempt.exceptionOrNull()
                    ?: IllegalStateException("Apple Music ALAC did not resolve")
                throw IllegalStateException(
                    "Apple Music ALAC failed: ${appleError.message ?: appleError.javaClass.simpleName}",
                    appleError,
                )
            }
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
        val qobuzError = qobuzAttempt.exceptionOrNull() ?: IllegalStateException("Qobuz failed")
        val appleError = appleAttempt.exceptionOrNull() ?: IllegalStateException("Apple Music failed")
        throw QobuzAudioProvider.QobuzResolutionException(
            "Qobuz failed: ${qobuzError.message ?: qobuzError.javaClass.simpleName}; ${deezerDetail}${instagramDetail}Apple Music failed: ${appleError.message ?: appleError.javaClass.simpleName}; SoundCloud failed: ${soundCloudError.message ?: soundCloudError.javaClass.simpleName}; YouTube failed: ${youtubeError.message ?: youtubeError.javaClass.simpleName}",
            youtubeError,
        )
    }

    private suspend fun resolveYouTubeFallback(mediaId: String): DownloadStreamResolution {
        val resolved = YouTubeAudioProvider.resolve(mediaId)
        Timber.tag(TAG).i(
            "Using YouTube AAC fallback for download $mediaId: itag=${resolved.itag}, bitrate=${resolved.bitrate}",
        )
        return DownloadStreamResolution(
            uri = resolved.mediaUri,
            expiresAtMs = resolved.expiresAtMs,
            cacheKey = youtubeFallbackCacheKey(mediaId),
            format = youtubeFallbackFormat(mediaId, resolved),
        )
    }

    private fun buildAppleMusicQuery(
        mediaId: String,
        song: Song?,
        wrapperHost: String = AppleMusicWrapperManagerProvider.DEFAULT_HOST,
        wrapperSecure: Boolean = true,
        audioMode: AppleMusicWrapperManagerProvider.WrapperMode = AppleMusicWrapperManagerProvider.WrapperMode.ALAC,
        highWorkerMode: Boolean = false,
    ): AppleMusicSongResolver.Query {
        return AppleMusicSongResolver.Query(
            mediaId = mediaId,
            title = song?.song?.title ?: mediaId,
            artists = song?.orderedArtists?.map { it.name }.orEmpty(),
            album = song?.song?.albumName ?: song?.album?.title,
            isrc = ProviderIsrc.firstOf(mediaId, song?.song?.id),
            durationMs = song?.song?.duration
                ?.takeIf { it > 0 }
                ?.toLong()
                ?.times(1000L),
            explicit = song?.song?.explicit,
            wrapperHost = wrapperHost,
            wrapperSecure = wrapperSecure,
            audioMode = audioMode,
            highWorkerMode = highWorkerMode,
        )
    }

    private fun Context.currentAppleMusicWrapperMode(): AppleMusicWrapperManagerProvider.WrapperMode {
        if (dataStore.get(AppleMusicForceAlacKey, false)) {
            return AppleMusicWrapperManagerProvider.WrapperMode.ALAC
        }
        return dataStore
            .get(AppleMusicAudioQualityKey)
            .toEnum(AppleMusicAudioQuality.ALAC)
            .toAppleMusicWrapperMode()
    }

    private fun AppleMusicAudioQuality.toAppleMusicWrapperMode(): AppleMusicWrapperManagerProvider.WrapperMode =
        when (this) {
            AppleMusicAudioQuality.ALAC -> AppleMusicWrapperManagerProvider.WrapperMode.ALAC
            AppleMusicAudioQuality.AAC -> AppleMusicWrapperManagerProvider.WrapperMode.AAC
            AppleMusicAudioQuality.DOLBY_ATMOS -> AppleMusicWrapperManagerProvider.WrapperMode.DOLBY_ATMOS
        }

    private fun buildQobuzQuery(
        context: Context,
        mediaId: String,
        song: Song?,
    ): QobuzAudioProvider.Query {
        val backend = context.dataStore.get(QobuzBackendKey).toEnum(QobuzBackend.JUMO)
        val country = context.dataStore.get(QobuzCountryKey, "US")
            .trim()
            .uppercase(Locale.US)
            .takeIf { it.matches(Regex("[A-Z]{2}")) }
            ?: "US"
        return QobuzAudioProvider.Query(
            mediaId = mediaId,
            title = song?.song?.title ?: mediaId,
            artists = song?.orderedArtists?.map { it.name }.orEmpty(),
            album = song?.song?.albumName ?: song?.album?.title,
            isrc = ProviderIsrc.firstOf(mediaId, song?.song?.id),
            durationMs = song?.song?.duration
                ?.takeIf { it > 0 }
                ?.toLong()
                ?.times(1000L),
            countryCode = country,
            backend = when (backend) {
                QobuzBackend.TRYPT -> QobuzAudioProvider.ResolverBackend.TRYPT
                QobuzBackend.JUMO -> QobuzAudioProvider.ResolverBackend.JUMO
                QobuzBackend.MONOCHROME -> QobuzAudioProvider.ResolverBackend.MONOCHROME
                QobuzBackend.SCAVENGER -> QobuzAudioProvider.ResolverBackend.SCAVENGER
                QobuzBackend.KENNY -> QobuzAudioProvider.ResolverBackend.KENNY
                QobuzBackend.SQUID -> QobuzAudioProvider.ResolverBackend.SQUID
            },
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

    val downloadNotificationHelper =
        DownloadNotificationHelper(context, ExoDownloadService.CHANNEL_ID)

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
        private const val APPLE_MUSIC_WRAPPER_ITAG = 100_001
        private const val QOBUZ_FALLBACK_ITAG = 100_027
        private const val DEEZER_FALLBACK_ITAG = 100_033
        private const val SOUNDCLOUD_FALLBACK_ITAG = 100_031
        private const val INSTAGRAM_FALLBACK_ITAG = 100_041
        private const val OLD_APPLE_WRAPPER_CACHE_PREFIX = "apple-wrapper-alac:"
        private const val OLD_APPLE_WRAPPER_CACHE_PREFIX_V2 = "apple-wrapper-alac-v2:"
        private const val APPLE_WRAPPER_CACHE_PREFIX = "apple-wrapper-alac-v3:"
        private const val OLD_QOBUZ_FALLBACK_CACHE_PREFIX = "qobuz-fallback:"
        private const val QOBUZ_FALLBACK_CACHE_PREFIX = "qobuz-fallback-v2:"
        private const val DEEZER_FALLBACK_CACHE_PREFIX = "deezer-fallback-audio:"
        private const val SOUNDCLOUD_FALLBACK_CACHE_PREFIX = "soundcloud-fallback-mp3:"
        private const val INSTAGRAM_FALLBACK_CACHE_PREFIX = "instagram-fallback-audio:"
        private const val YOUTUBE_FALLBACK_CACHE_PREFIX = "youtube-fallback-aac:"

        private fun appleWrapperCacheKey(mediaId: String) = "$APPLE_WRAPPER_CACHE_PREFIX$mediaId"

        private fun qobuzFallbackCacheKey(mediaId: String) = "$QOBUZ_FALLBACK_CACHE_PREFIX$mediaId"

        private fun deezerFallbackCacheKey(mediaId: String) = "$DEEZER_FALLBACK_CACHE_PREFIX$mediaId"

        private fun soundCloudFallbackCacheKey(mediaId: String) = "$SOUNDCLOUD_FALLBACK_CACHE_PREFIX$mediaId"

        private fun instagramFallbackCacheKey(mediaId: String) = "$INSTAGRAM_FALLBACK_CACHE_PREFIX$mediaId"

        private fun youtubeFallbackCacheKey(mediaId: String) = "$YOUTUBE_FALLBACK_CACHE_PREFIX$mediaId"

        private fun mediaIdFromDataSpecKey(key: String) = key
            .removePrefix(APPLE_WRAPPER_CACHE_PREFIX)
            .removePrefix(OLD_APPLE_WRAPPER_CACHE_PREFIX_V2)
            .removePrefix(OLD_APPLE_WRAPPER_CACHE_PREFIX)
            .removePrefix(OLD_QOBUZ_FALLBACK_CACHE_PREFIX)
            .removePrefix(QOBUZ_FALLBACK_CACHE_PREFIX)
            .removePrefix(DEEZER_FALLBACK_CACHE_PREFIX)
            .removePrefix(SOUNDCLOUD_FALLBACK_CACHE_PREFIX)
            .removePrefix(INSTAGRAM_FALLBACK_CACHE_PREFIX)
            .removePrefix(YOUTUBE_FALLBACK_CACHE_PREFIX)

        private fun appleWrapperFormat(
            mediaId: String,
            bitrate: Int = 0,
            sampleRate: Int? = null,
            audioMode: AppleMusicWrapperManagerProvider.WrapperMode = AppleMusicWrapperManagerProvider.WrapperMode.ALAC,
        ) = FormatEntity(
            id = mediaId,
            itag = APPLE_MUSIC_WRAPPER_ITAG,
            mimeType = "audio/mp4",
            codecs = audioMode.formatCodec(),
            bitrate = bitrate,
            sampleRate = sampleRate,
            contentLength = 0L,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            playbackUrl = null,
        )

        private fun AppleMusicWrapperManagerProvider.WrapperMode.formatCodec(): String =
            when (this) {
                AppleMusicWrapperManagerProvider.WrapperMode.ALAC -> "alac"
                AppleMusicWrapperManagerProvider.WrapperMode.AAC -> "mp4a.40.2"
                AppleMusicWrapperManagerProvider.WrapperMode.DOLBY_ATMOS -> "ec-3"
            }

        private fun qobuzFallbackFormat(
            mediaId: String,
            resolved: QobuzAudioProvider.Resolved,
        ) = FormatEntity(
            id = mediaId,
            itag = QOBUZ_FALLBACK_ITAG,
            mimeType = resolved.mimeType,
            codecs = resolved.codecs,
            bitrate = resolved.bitrate,
            sampleRate = resolved.sampleRate,
            contentLength = 0L,
            loudnessDb = null,
            perceptualLoudnessDb = null,
            playbackUrl = null,
        )

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
