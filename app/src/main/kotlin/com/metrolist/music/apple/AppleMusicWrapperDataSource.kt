/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.apple

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

@UnstableApi
class AppleMusicWrapperDataSource(
    private val client: OkHttpClient = defaultClient,
) : BaseDataSource(true) {
    private var opened = false
    private var currentUri: Uri? = null
    private var currentStream: InputStream? = null
    private var currentRequest: Request? = null
    private var currentDataSpec: DataSpec? = null
    private var retriedFreshM3u8 = false
    private var bytesReadFromOpen = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val rawRequest = Request.fromUri(dataSpec.uri)
        val resourceName = rawRequest.resourceName
        val request = if (rawRequest.isVirtualHlsRootResource) {
            rawRequest.freshenM3u8IfNeeded()
        } else {
            rawRequest
        }
        currentUri = dataSpec.uri
        currentRequest = request
        currentDataSpec = dataSpec
        retriedFreshM3u8 = false
        bytesReadFromOpen = 0L
        val length = try {
            if (resourceName != null && request.isVirtualHlsResource) {
                openVirtualHlsResource(request, resourceName, dataSpec)
            } else {
                openStream(request, dataSpec)
            }
        } catch (error: Exception) {
            if (AppleMusicDecryptPipeline.isAlacStartupTimeout(error)) {
                throw IOException("Apple Music wrapper ALAC failed: ${error.message ?: error.javaClass.simpleName}", error)
            }
            if (request.isVirtualHlsResource) {
                throw IOException("Apple Music wrapper ALAC failed: ${error.message ?: error.javaClass.simpleName}", error)
            } else {
                retryWithFreshM3u8(error)
                    ?: throw IOException("Apple Music wrapper ALAC failed: ${error.message ?: error.javaClass.simpleName}", error)
            }
        }
        opened = true
        transferStarted(dataSpec)
        return length.takeIf { it >= 0L } ?: C.LENGTH_UNSET.toLong()
    }

    private fun openVirtualHlsResource(
        request: Request,
        resourceName: String,
        dataSpec: DataSpec,
    ): Long {
        val bytes = AppleMusicDecryptPipeline.openVirtualHlsResource(
            request = request.toVirtualHlsRequest(),
            resourceName = resourceName,
            resourceUri = request::resourceUri,
            client = client,
            highWorkerMode = request.highWorkerMode,
        )
        val position = dataSpec.position.coerceIn(0L, bytes.size.toLong()).toInt()
        val available = bytes.size - position
        val requested = dataSpec.length.takeIf { it != C.LENGTH_UNSET.toLong() }
            ?.coerceAtMost(available.toLong())
            ?.coerceAtLeast(0L)
            ?.toInt()
            ?: available
        currentStream = ByteArrayInputStream(bytes, position, requested)
        return requested.toLong()
    }

    private fun openStream(
        request: Request,
        dataSpec: DataSpec,
    ): Long {
        val (stream, length) = AppleMusicDecryptPipeline.openDecryptedStream(
            adamId = request.adamId,
            m3u8Url = request.m3u8Url,
            host = request.host,
            secure = request.secure,
            mode = AppleMusicWrapperManagerProvider.WrapperMode.ALAC,
            client = client,
            start = dataSpec.position,
            requestedLength = dataSpec.length,
            durationMs = request.durationMs,
            highWorkerMode = request.highWorkerMode,
        )
        currentStream = stream
        return length
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        val read = try {
            currentStream?.read(buffer, offset, length)
        } catch (error: Exception) {
            if (AppleMusicDecryptPipeline.isAlacStartupTimeout(error)) {
                throw IOException("Apple Music wrapper ALAC failed: ${error.message ?: error.javaClass.simpleName}", error)
            }
            if (retryWithFreshM3u8(error) != null) {
                currentStream?.read(buffer, offset, length)
            } else {
                throw IOException("Apple Music wrapper ALAC failed: ${error.message ?: error.javaClass.simpleName}", error)
            }
        } ?: return C.RESULT_END_OF_INPUT
        if (read == -1) return C.RESULT_END_OF_INPUT
        bytesReadFromOpen += read
        bytesTransferred(read)
        return read
    }

    private fun retryWithFreshM3u8(error: Throwable): Long? {
        val request = currentRequest ?: return null
        val dataSpec = currentDataSpec ?: return null
        if (retriedFreshM3u8) return null
        retriedFreshM3u8 = true
        runCatching { currentStream?.close() }.onFailure { closeError ->
            Timber.tag(TAG).w(closeError, "Failed to close stale Apple wrapper ALAC stream before retry")
        }
        currentStream = null
        AppleMusicDecryptPipeline.clearMemoryCaches()

        return runCatching {
            val retrySpec = dataSpec.subrange(bytesReadFromOpen)
            val freshRequest = request.withFreshM3u8()
            currentRequest = freshRequest
            currentDataSpec = retrySpec
            bytesReadFromOpen = 0L
            openStream(freshRequest, retrySpec)
        }.onFailure { retryError ->
            Timber.tag(TAG).w(retryError, "Apple wrapper ALAC fresh M3U8 retry failed after ${error.message ?: error.javaClass.simpleName}")
        }.getOrNull()
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        currentUri = null
        currentStream?.close()
        currentStream = null
        currentRequest = null
        currentDataSpec = null
        retriedFreshM3u8 = false
        bytesReadFromOpen = 0L
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    data class Request(
        val mediaId: String,
        val adamId: String,
        val m3u8Url: String,
        val host: String,
        val secure: Boolean,
        val issuedAtMs: Long?,
        val durationMs: Long?,
        val title: String?,
        val resourceName: String?,
        val highWorkerMode: Boolean,
    ) {
        companion object {
            fun fromUri(uri: Uri): Request {
                val pathSegments = uri.pathSegments
                val mediaId = pathSegments.firstOrNull()
                    ?: throw AppleMusicWrapperManagerProvider.WrapperManagerException("Apple wrapper URI is missing mediaId")
                val resourceName = pathSegments.getOrNull(1)
                val adamId = uri.getQueryParameter(PARAM_ADAM_ID)
                val m3u8Url = uri.getQueryParameter(PARAM_M3U8)
                if (adamId.isNullOrBlank() || m3u8Url.isNullOrBlank()) {
                    return resolvePendingUri(uri, mediaId, resourceName)
                }
                return Request(
                    mediaId = mediaId,
                    adamId = adamId,
                    m3u8Url = m3u8Url,
                    host = uri.getQueryParameter(PARAM_HOST)
                        ?: DEFAULT_HOST,
                    secure = uri.getQueryParameter(PARAM_SECURE)?.toBooleanStrictOrNull() ?: true,
                    issuedAtMs = uri.getQueryParameter(PARAM_ISSUED_AT_MS)?.toLongOrNull(),
                    durationMs = uri.getQueryParameter(PARAM_DURATION_MS)?.toLongOrNull(),
                    title = uri.getQueryParameter(PARAM_TITLE),
                    resourceName = resourceName,
                    highWorkerMode = uri.getQueryParameter(PARAM_HIGH_WORKER_MODE)?.toBooleanStrictOrNull() ?: false,
                )
            }

            private fun resolvePendingUri(
                uri: Uri,
                mediaId: String,
                resourceName: String?,
            ): Request {
                val title = uri.getQueryParameter(PARAM_TITLE)
                    ?.takeIf { it.isNotBlank() }
                    ?: mediaId
                val artists = uri.getQueryParameters(PARAM_ARTIST)
                    .mapNotNull { it.takeIf(String::isNotBlank) }
                val resolved = AppleMusicSongResolver.resolve(
                    AppleMusicSongResolver.Query(
                        mediaId = mediaId,
                        title = title,
                        artists = artists,
                        album = uri.getQueryParameter(PARAM_ALBUM)?.takeIf { it.isNotBlank() },
                        isrc = null,
                        durationMs = uri.getQueryParameter(PARAM_DURATION_MS)?.toLongOrNull(),
                        explicit = uri.getQueryParameter(PARAM_EXPLICIT)?.toBooleanStrictOrNull(),
                        wrapperHost = uri.getQueryParameter(PARAM_HOST)
                            ?: AppleMusicWrapperManagerProvider.DEFAULT_HOST,
                        wrapperSecure = uri.getQueryParameter(PARAM_SECURE)?.toBooleanStrictOrNull() ?: true,
                        highWorkerMode = uri.getQueryParameter(PARAM_HIGH_WORKER_MODE)?.toBooleanStrictOrNull() ?: false,
                    ),
                )
                return fromUri(Uri.parse(resolved.mediaUri)).copy(resourceName = resourceName)
            }
        }

        val isVirtualHlsResource: Boolean
            get() = resourceName == HLS_MASTER_RESOURCE ||
                resourceName == HLS_MEDIA_RESOURCE ||
                resourceName == HLS_INIT_RESOURCE ||
                resourceName?.let { it.startsWith(HLS_SEGMENT_PREFIX) && it.endsWith(HLS_SEGMENT_SUFFIX) } == true

        val isVirtualHlsRootResource: Boolean
            get() = resourceName == HLS_MASTER_RESOURCE || resourceName == HLS_MEDIA_RESOURCE

        fun freshenM3u8IfNeeded(): Request {
            val issuedAt = issuedAtMs
            val shouldRefresh = issuedAt == null ||
                System.currentTimeMillis() - issuedAt > M3U8_REFRESH_AFTER_MS
            if (!shouldRefresh) return this
            return runCatching { withFreshM3u8() }
                .onFailure { error ->
                    Timber.tag(TAG).w(error, "Failed to refresh stale Apple wrapper M3U8 before open")
                }
                .getOrDefault(this)
        }

        fun withFreshM3u8(): Request {
            val freshM3u8 = AppleMusicWrapperManagerProvider.getM3u8WithFallback(
                adamId = adamId,
                preferredHost = host,
                preferredSecure = secure,
                mode = AppleMusicWrapperManagerProvider.WrapperMode.ALAC,
            )
            return copy(
                m3u8Url = freshM3u8.url,
                host = freshM3u8.host,
                secure = freshM3u8.secure,
                issuedAtMs = System.currentTimeMillis(),
            )
        }

        fun toVirtualHlsRequest(): AppleMusicDecryptPipeline.VirtualHlsRequest {
            return AppleMusicDecryptPipeline.VirtualHlsRequest(
                sessionKey = listOf(mediaId, adamId, issuedAtMs ?: 0L, m3u8Url).joinToString("|"),
                mediaId = mediaId,
                adamId = adamId,
                m3u8Url = m3u8Url,
                host = host,
                secure = secure,
                durationMs = durationMs,
                highWorkerMode = highWorkerMode,
            )
        }

        fun resourceUri(resourceName: String): String {
            val builder = Uri.Builder()
                .scheme(SCHEME)
                .authority(AUTHORITY)
                .appendPath(mediaId)
                .appendPath(resourceName)
                .appendQueryParameter(PARAM_ADAM_ID, adamId)
                .appendQueryParameter(PARAM_M3U8, m3u8Url)
                .appendQueryParameter(PARAM_HOST, host)
                .appendQueryParameter(PARAM_SECURE, secure.toString())
                .appendQueryParameter(PARAM_ISSUED_AT_MS, (issuedAtMs ?: System.currentTimeMillis()).toString())
            durationMs?.let { builder.appendQueryParameter(PARAM_DURATION_MS, it.toString()) }
            title?.takeIf { it.isNotBlank() }?.let { builder.appendQueryParameter(PARAM_TITLE, it) }
            if (highWorkerMode) {
                builder.appendQueryParameter(PARAM_HIGH_WORKER_MODE, true.toString())
            }
            return builder.build().toString()
        }
    }

    class Factory(
        private val client: OkHttpClient = defaultClient,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = AppleMusicWrapperDataSource(client)
    }

    companion object {
        private const val TAG = "AppleALAC"
        const val SCHEME = "apple-wrapper"
        private const val AUTHORITY = "alac"
        private const val DEFAULT_HOST = AppleMusicWrapperManagerProvider.DEFAULT_HOST
        private const val PARAM_ADAM_ID = "adamId"
        private const val PARAM_M3U8 = "m3u8"
        private const val PARAM_HOST = "host"
        private const val PARAM_SECURE = "secure"
        private const val PARAM_ISSUED_AT_MS = "issuedAtMs"
        private const val PARAM_DURATION_MS = "durationMs"
        private const val PARAM_TITLE = "title"
        private const val PARAM_ARTIST = "artist"
        private const val PARAM_ALBUM = "album"
        private const val PARAM_EXPLICIT = "explicit"
        private const val PARAM_PENDING = "pending"
        private const val PARAM_HIGH_WORKER_MODE = "highWorkerMode"
        private const val M3U8_REFRESH_AFTER_MS = 4 * 60 * 1000L
        private const val HLS_MASTER_RESOURCE = "master.m3u8"
        private const val HLS_MEDIA_RESOURCE = "media.m3u8"
        private const val HLS_INIT_RESOURCE = "init.mp4"
        private const val HLS_SEGMENT_PREFIX = "seg-"
        private const val HLS_SEGMENT_SUFFIX = ".m4s"

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()

        fun isAppleUri(uri: Uri): Boolean = uri.scheme == SCHEME

        fun mediaIdFromUri(uri: Uri): String? =
            uri.takeIf(::isAppleUri)
                ?.pathSegments
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }

        fun toProgressiveStreamUri(
            uri: Uri,
            highWorkerMode: Boolean = false,
        ): Uri {
            if (!isAppleUri(uri)) return uri
            val mediaId = mediaIdFromUri(uri) ?: return uri
            val resolvedHighWorkerMode = highWorkerMode ||
                (uri.getQueryParameter(PARAM_HIGH_WORKER_MODE)?.toBooleanStrictOrNull() ?: false)
            val builder = Uri.Builder()
                .scheme(SCHEME)
                .authority(AUTHORITY)
                .appendPath(mediaId)
            uri.queryParameterNames.forEach { name ->
                if (name == PARAM_HIGH_WORKER_MODE) return@forEach
                uri.getQueryParameters(name).forEach { value ->
                    builder.appendQueryParameter(name, value)
                }
            }
            if (resolvedHighWorkerMode) {
                builder.appendQueryParameter(PARAM_HIGH_WORKER_MODE, true.toString())
            }
            return builder.build()
        }

        fun buildPendingUri(
            mediaId: String,
            title: String,
            artists: List<String>,
            album: String?,
            durationMs: Long?,
            explicit: Boolean?,
            wrapperHost: String = AppleMusicWrapperManagerProvider.DEFAULT_HOST,
            wrapperSecure: Boolean = true,
            highWorkerMode: Boolean = false,
        ): String {
            val builder = Uri.Builder()
                .scheme(SCHEME)
                .authority(AUTHORITY)
                .appendPath(mediaId)
                .appendPath(HLS_MASTER_RESOURCE)
                .appendQueryParameter(PARAM_PENDING, true.toString())
                .appendQueryParameter(PARAM_TITLE, title)
                .appendQueryParameter(PARAM_HOST, AppleMusicWrapperManagerProvider.normalizeHost(wrapperHost))
                .appendQueryParameter(PARAM_SECURE, wrapperSecure.toString())
            artists
                .filter { it.isNotBlank() }
                .forEach { builder.appendQueryParameter(PARAM_ARTIST, it) }
            album?.takeIf { it.isNotBlank() }?.let { builder.appendQueryParameter(PARAM_ALBUM, it) }
            durationMs?.takeIf { it > 0L }?.let { builder.appendQueryParameter(PARAM_DURATION_MS, it.toString()) }
            explicit?.let { builder.appendQueryParameter(PARAM_EXPLICIT, it.toString()) }
            if (highWorkerMode) {
                builder.appendQueryParameter(PARAM_HIGH_WORKER_MODE, true.toString())
            }
            return builder.build().toString()
        }

        fun buildUri(
            mediaId: String,
            adamId: String,
            m3u8Url: String,
            host: String,
            secure: Boolean,
            durationMs: Long?,
            title: String?,
            highWorkerMode: Boolean = false,
        ): String {
            val builder = Uri.Builder()
                .scheme(SCHEME)
                .authority(AUTHORITY)
                .appendPath(mediaId)
                .appendPath(HLS_MASTER_RESOURCE)
                .appendQueryParameter(PARAM_ADAM_ID, adamId)
                .appendQueryParameter(PARAM_M3U8, m3u8Url)
                .appendQueryParameter(PARAM_HOST, host)
                .appendQueryParameter(PARAM_SECURE, secure.toString())
                .appendQueryParameter(PARAM_ISSUED_AT_MS, System.currentTimeMillis().toString())
            durationMs?.let { builder.appendQueryParameter(PARAM_DURATION_MS, it.toString()) }
            title?.takeIf { it.isNotBlank() }?.let { builder.appendQueryParameter(PARAM_TITLE, it) }
            if (highWorkerMode) {
                builder.appendQueryParameter(PARAM_HIGH_WORKER_MODE, true.toString())
            }
            return builder.build().toString()
        }
    }
}

@UnstableApi
class AppleMusicAwareDataSourceFactory(
    private val normalFactory: DataSource.Factory,
    private val appleFactory: DataSource.Factory = AppleMusicWrapperDataSource.Factory(),
) : DataSource.Factory {
    override fun createDataSource(): DataSource {
        return AppleMusicAwareDataSource(
            normal = normalFactory.createDataSource(),
            apple = appleFactory.createDataSource(),
        )
    }
}

@UnstableApi
private class AppleMusicAwareDataSource(
    private val normal: DataSource,
    private val apple: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        normal.addTransferListener(transferListener)
        apple.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val selected = if (AppleMusicWrapperDataSource.isAppleUri(dataSpec.uri)) apple else normal
        active = selected
        return selected.open(dataSpec)
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        return active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> {
        return active?.responseHeaders ?: emptyMap()
    }

    override fun close() {
        active?.close()
        active = null
    }
}
