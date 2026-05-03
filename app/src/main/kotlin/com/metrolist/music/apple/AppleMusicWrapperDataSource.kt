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
        val request = Request.fromUri(dataSpec.uri).freshenM3u8IfNeeded()
        currentUri = dataSpec.uri
        currentRequest = request
        currentDataSpec = dataSpec
        retriedFreshM3u8 = false
        bytesReadFromOpen = 0L
        val length = try {
            openStream(request, dataSpec)
        } catch (error: Throwable) {
            retryWithFreshM3u8(error)
                ?: throw IOException("Apple Music wrapper ALAC failed: ${error.message ?: error.javaClass.simpleName}", error)
        }
        opened = true
        transferStarted(dataSpec)
        return length.takeIf { it >= 0L } ?: C.LENGTH_UNSET.toLong()
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
            highWorkerMode = false,
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
        } catch (error: Throwable) {
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
        runCatching { currentStream?.close() }
        currentStream = null
        AppleMusicDecryptPipeline.clearMemoryCaches()

        return runCatching {
            val retrySpec = dataSpec.subrange(bytesReadFromOpen)
            val freshRequest = request.withFreshM3u8()
            currentRequest = freshRequest
            currentDataSpec = retrySpec
            bytesReadFromOpen = 0L
            openStream(freshRequest, retrySpec)
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
        val adamId: String,
        val m3u8Url: String,
        val host: String,
        val secure: Boolean,
        val issuedAtMs: Long?,
        val durationMs: Long?,
        val title: String?,
    ) {
        companion object {
            fun fromUri(uri: Uri): Request {
                return Request(
                    adamId = uri.getQueryParameter(PARAM_ADAM_ID)
                        ?: throw AppleMusicWrapperManagerProvider.WrapperManagerException("Apple wrapper URI is missing adamId"),
                    m3u8Url = uri.getQueryParameter(PARAM_M3U8)
                        ?: throw AppleMusicWrapperManagerProvider.WrapperManagerException("Apple wrapper URI is missing m3u8"),
                    host = uri.getQueryParameter(PARAM_HOST)
                        ?: DEFAULT_HOST,
                    secure = uri.getQueryParameter(PARAM_SECURE)?.toBooleanStrictOrNull() ?: true,
                    issuedAtMs = uri.getQueryParameter(PARAM_ISSUED_AT_MS)?.toLongOrNull(),
                    durationMs = uri.getQueryParameter(PARAM_DURATION_MS)?.toLongOrNull(),
                    title = uri.getQueryParameter(PARAM_TITLE),
                )
            }
        }

        fun freshenM3u8IfNeeded(): Request {
            val issuedAt = issuedAtMs
            val shouldRefresh = issuedAt == null ||
                System.currentTimeMillis() - issuedAt > M3U8_REFRESH_AFTER_MS
            if (!shouldRefresh) return this
            return runCatching { withFreshM3u8() }.getOrDefault(this)
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
    }

    class Factory(
        private val client: OkHttpClient = defaultClient,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = AppleMusicWrapperDataSource(client)
    }

    companion object {
        const val SCHEME = "apple-wrapper"
        private const val AUTHORITY = "alac"
        private const val DEFAULT_HOST = "wm.wol.moe"
        private const val PARAM_ADAM_ID = "adamId"
        private const val PARAM_M3U8 = "m3u8"
        private const val PARAM_HOST = "host"
        private const val PARAM_SECURE = "secure"
        private const val PARAM_ISSUED_AT_MS = "issuedAtMs"
        private const val PARAM_DURATION_MS = "durationMs"
        private const val PARAM_TITLE = "title"
        private const val M3U8_REFRESH_AFTER_MS = 4 * 60 * 1000L

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()

        fun isAppleUri(uri: Uri): Boolean = uri.scheme == SCHEME

        fun buildUri(
            mediaId: String,
            adamId: String,
            m3u8Url: String,
            host: String,
            secure: Boolean,
            durationMs: Long?,
            title: String?,
        ): String {
            val builder = Uri.Builder()
                .scheme(SCHEME)
                .authority(AUTHORITY)
                .appendPath(mediaId)
                .appendQueryParameter(PARAM_ADAM_ID, adamId)
                .appendQueryParameter(PARAM_M3U8, m3u8Url)
                .appendQueryParameter(PARAM_HOST, host)
                .appendQueryParameter(PARAM_SECURE, secure.toString())
                .appendQueryParameter(PARAM_ISSUED_AT_MS, System.currentTimeMillis().toString())
            durationMs?.let { builder.appendQueryParameter(PARAM_DURATION_MS, it.toString()) }
            title?.takeIf { it.isNotBlank() }?.let { builder.appendQueryParameter(PARAM_TITLE, it) }
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
