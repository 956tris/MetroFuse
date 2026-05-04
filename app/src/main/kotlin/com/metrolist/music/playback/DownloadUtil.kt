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
import com.metrolist.music.constants.QobuzBackend
import com.metrolist.music.constants.QobuzBackendKey
import com.metrolist.music.constants.QobuzCountryKey
import com.metrolist.music.constants.QobuzFallbackEnabledKey
import com.metrolist.music.constants.PreferQobuzKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.di.DownloadCache
import com.metrolist.music.di.PlayerCache
import com.metrolist.music.extensions.toEnum
import com.metrolist.music.qobuz.QobuzAudioProvider
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
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadUtil
@Inject
constructor(
    @ApplicationContext context: Context,
    val database: MusicDatabase,
    val databaseProvider: DatabaseProvider,
    @DownloadCache val downloadCache: SimpleCache,
    @PlayerCache val playerCache: SimpleCache,
) {
    private val TAG = "DownloadUtil"
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

    val downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    private val dataSourceFactory =
        ResolvingDataSource.Factory(
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
                                    chain.proceed(request)
                                }.build(),
                        ),
                    ),
            ),
        ) { dataSpec ->
            val mediaId = dataSpec.key?.let(::mediaIdFromDataSpecKey) ?: error("No media id")
            val streamSelectionKey = currentStreamSelectionKey(context)

            if (AppleMusicWrapperDataSource.isAppleUri(dataSpec.uri)) {
                return@Factory dataSpec
                    .buildUpon()
                    .setKey(appleWrapperCacheKey(mediaId))
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
        val qobuzEnabled = context.dataStore.get(QobuzFallbackEnabledKey, true)
        val preferQobuz = context.dataStore.get(PreferQobuzKey, false)
        val qobuzBackend = context.dataStore.get(QobuzBackendKey).toEnum(QobuzBackend.JUMO)
        val qobuzCountry = context.dataStore.get(QobuzCountryKey, "US")
            .trim()
            .uppercase(Locale.US)
            .takeIf { it.matches(Regex("[A-Z]{2}")) }
            ?: "US"
        return listOf(
            "qobuz=$qobuzEnabled",
            "prefer=$preferQobuz",
            "backend=${qobuzBackend.name}",
            "country=$qobuzCountry",
        ).joinToString(";")
    }

    private suspend fun resolveDownloadStream(
        context: Context,
        mediaId: String,
        song: Song?,
    ): DownloadStreamResolution {
        val qobuzEnabled = context.dataStore.get(QobuzFallbackEnabledKey, true)
        if (qobuzEnabled && context.dataStore.get(PreferQobuzKey, false)) {
            val qobuzAttempt = runCatching {
                QobuzAudioProvider.resolve(buildQobuzQuery(context, mediaId, song))
            }
            qobuzAttempt.getOrNull()?.let { resolved ->
                return DownloadStreamResolution(
                    uri = resolved.mediaUri,
                    expiresAtMs = resolved.expiresAtMs,
                    cacheKey = qobuzFallbackCacheKey(mediaId),
                    format = qobuzFallbackFormat(mediaId, resolved),
                )
            }

            val qobuzError = qobuzAttempt.exceptionOrNull() ?: IllegalStateException("Qobuz preferred stream failed")
            val appleAttempt = runCatching {
                AppleMusicSongResolver.resolve(buildAppleMusicQuery(mediaId, song))
            }
            appleAttempt.getOrNull()?.let { resolved ->
                return DownloadStreamResolution(
                    uri = resolved.mediaUri,
                    expiresAtMs = resolved.expiresAtMs,
                    cacheKey = appleWrapperCacheKey(mediaId),
                    format = appleWrapperFormat(mediaId, resolved.bitrate, resolved.sampleRate),
                )
            }

            val appleError = appleAttempt.exceptionOrNull() ?: IllegalStateException("Apple Music failed")
            val youtubeAttempt = runCatching {
                resolveYouTubeFallback(mediaId)
            }
            youtubeAttempt.getOrNull()?.let { return it }

            val youtubeError = youtubeAttempt.exceptionOrNull() ?: IllegalStateException("YouTube fallback failed")
            throw QobuzAudioProvider.QobuzResolutionException(
                "Qobuz failed: ${qobuzError.message ?: qobuzError.javaClass.simpleName}; Apple Music failed: ${appleError.message ?: appleError.javaClass.simpleName}; YouTube failed: ${youtubeError.message ?: youtubeError.javaClass.simpleName}",
                youtubeError,
            )
        }

        val appleAttempt = runCatching {
            AppleMusicSongResolver.resolve(buildAppleMusicQuery(mediaId, song))
        }
        appleAttempt.getOrNull()?.let { resolved ->
            return DownloadStreamResolution(
                uri = resolved.mediaUri,
                expiresAtMs = resolved.expiresAtMs,
                cacheKey = appleWrapperCacheKey(mediaId),
                format = appleWrapperFormat(mediaId, resolved.bitrate, resolved.sampleRate),
            )
        }

        val appleError = appleAttempt.exceptionOrNull() ?: IllegalStateException("Apple Music failed")
        if (qobuzEnabled) {
            val qobuzAttempt = runCatching {
                QobuzAudioProvider.resolve(buildQobuzQuery(context, mediaId, song))
            }
            qobuzAttempt.getOrNull()?.let { resolved ->
                return DownloadStreamResolution(
                    uri = resolved.mediaUri,
                    expiresAtMs = resolved.expiresAtMs,
                    cacheKey = qobuzFallbackCacheKey(mediaId),
                    format = qobuzFallbackFormat(mediaId, resolved),
                )
            }

            val qobuzError = qobuzAttempt.exceptionOrNull() ?: IllegalStateException("Qobuz fallback failed")
            val youtubeAttempt = runCatching {
                resolveYouTubeFallback(mediaId)
            }
            youtubeAttempt.getOrNull()?.let { return it }

            val youtubeError = youtubeAttempt.exceptionOrNull() ?: IllegalStateException("YouTube fallback failed")
            throw QobuzAudioProvider.QobuzResolutionException(
                "Apple Music failed: ${appleError.message ?: appleError.javaClass.simpleName}; Qobuz fallback failed: ${qobuzError.message ?: qobuzError.javaClass.simpleName}; YouTube failed: ${youtubeError.message ?: youtubeError.javaClass.simpleName}",
                youtubeError,
            )
        }

        val youtubeAttempt = runCatching {
            resolveYouTubeFallback(mediaId)
        }
        youtubeAttempt.getOrNull()?.let { return it }

        val youtubeError = youtubeAttempt.exceptionOrNull() ?: IllegalStateException("YouTube fallback failed")
        throw QobuzAudioProvider.QobuzResolutionException(
            "Apple Music failed: ${appleError.message ?: appleError.javaClass.simpleName}; YouTube failed: ${youtubeError.message ?: youtubeError.javaClass.simpleName}",
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
    ): AppleMusicSongResolver.Query {
        return AppleMusicSongResolver.Query(
            mediaId = mediaId,
            title = song?.song?.title ?: mediaId,
            artists = song?.orderedArtists?.map { it.name }.orEmpty(),
            album = song?.song?.albumName ?: song?.album?.title,
            durationMs = song?.song?.duration
                ?.takeIf { it > 0 }
                ?.toLong()
                ?.times(1000L),
            explicit = song?.song?.explicit,
        )
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
            isrc = null,
            durationMs = song?.song?.duration
                ?.takeIf { it > 0 }
                ?.toLong()
                ?.times(1000L),
            countryCode = country,
            backend = when (backend) {
                QobuzBackend.JUMO -> QobuzAudioProvider.ResolverBackend.JUMO
                QobuzBackend.KENNY -> QobuzAudioProvider.ResolverBackend.KENNY
                QobuzBackend.SQUID -> QobuzAudioProvider.ResolverBackend.SQUID
            },
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

                        runCatching {
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
            )
        }

    init {
        val result = mutableMapOf<String, Download>()
        val cursor = downloadManager.downloadIndex.getDownloads()
        while (cursor.moveToNext()) {
            result[cursor.download.request.id] = cursor.download
        }
        downloads.value = result
    }

    fun getDownload(songId: String): Flow<Download?> = downloads.map { it[songId] }

    fun release() {
        scope.cancel()
    }

    private companion object {
        private const val APPLE_MUSIC_WRAPPER_ITAG = 100_001
        private const val QOBUZ_FALLBACK_ITAG = 100_027
        private const val APPLE_WRAPPER_CACHE_PREFIX = "apple-wrapper-alac:"
        private const val OLD_QOBUZ_FALLBACK_CACHE_PREFIX = "qobuz-fallback:"
        private const val QOBUZ_FALLBACK_CACHE_PREFIX = "qobuz-fallback-v2:"
        private const val YOUTUBE_FALLBACK_CACHE_PREFIX = "youtube-fallback-aac:"

        private fun appleWrapperCacheKey(mediaId: String) = "$APPLE_WRAPPER_CACHE_PREFIX$mediaId"

        private fun qobuzFallbackCacheKey(mediaId: String) = "$QOBUZ_FALLBACK_CACHE_PREFIX$mediaId"

        private fun youtubeFallbackCacheKey(mediaId: String) = "$YOUTUBE_FALLBACK_CACHE_PREFIX$mediaId"

        private fun mediaIdFromDataSpecKey(key: String) = key
            .removePrefix(APPLE_WRAPPER_CACHE_PREFIX)
            .removePrefix(OLD_QOBUZ_FALLBACK_CACHE_PREFIX)
            .removePrefix(QOBUZ_FALLBACK_CACHE_PREFIX)
            .removePrefix(YOUTUBE_FALLBACK_CACHE_PREFIX)

        private fun appleWrapperFormat(
            mediaId: String,
            bitrate: Int = 0,
            sampleRate: Int? = null,
        ) = FormatEntity(
            id = mediaId,
            itag = APPLE_MUSIC_WRAPPER_ITAG,
            mimeType = "audio/mp4",
            codecs = "alac",
            bitrate = bitrate,
            sampleRate = sampleRate,
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
