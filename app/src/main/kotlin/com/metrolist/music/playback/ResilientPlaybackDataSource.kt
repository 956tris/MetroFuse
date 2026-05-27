/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.EOFException
import java.io.IOException
import java.net.ProtocolException

internal class ResilientPlaybackDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        ResilientPlaybackDataSource(upstreamFactory.createDataSource())
}

private class ResilientPlaybackDataSource(
    private val upstream: DataSource,
) : DataSource {
    private var baseDataSpec: DataSpec? = null
    private var bytesReadFromBase = 0L
    private var readRetryCount = 0

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        baseDataSpec = dataSpec
        bytesReadFromBase = 0L
        readRetryCount = 0
        return upstream.open(dataSpec)
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        while (true) {
            try {
                val read = upstream.read(buffer, offset, length)
                if (read > 0) bytesReadFromBase += read
                return read
            } catch (error: IOException) {
                if (!error.isRecoverableUnexpectedEnd() || !reopenAfterUnexpectedEnd()) {
                    throw error
                }
            }
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        baseDataSpec = null
        bytesReadFromBase = 0L
        readRetryCount = 0
        upstream.close()
    }

    private fun reopenAfterUnexpectedEnd(): Boolean {
        val original = baseDataSpec ?: return false
        if (readRetryCount >= MAX_READ_RETRIES) return false

        val remainingLength: Long =
            if (original.length == C.LENGTH_UNSET.toLong()) {
                C.LENGTH_UNSET.toLong()
            } else {
                (original.length - bytesReadFromBase).coerceAtLeast(0L)
            }
        if (remainingLength == 0L) return false

        readRetryCount++
        runCatching { upstream.close() }
        val retrySpec =
            original
                .buildUpon()
                .setPosition(original.position + bytesReadFromBase)
                .setLength(remainingLength)
                .build()
        upstream.open(retrySpec)
        return true
    }

    private fun Throwable.isRecoverableUnexpectedEnd(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            val message = current.message.orEmpty()
            if (
                current is ProtocolException &&
                message.contains("unexpected end of stream", ignoreCase = true)
            ) {
                return true
            }
            if (
                current is EOFException &&
                message.contains("unexpected", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private companion object {
        private const val MAX_READ_RETRIES = 2
    }
}
