/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Pass-through [DataSource] that sniffs the MP4 `alac` magic cookie from the
 * head of Apple Music ALAC streams and reports the encoder-written average
 * bitrate plus stream sample rate.
 *
 * The Apple provider resolves ALAC with bitrate 0 (no estimate is presented
 * as real), so without this the UI can never show a number: the container
 * bitrate ExoPlayer reports is just the manifest echo and FLAC frame parsing
 * does not apply to MP4-framed ALAC. The cookie's avgBitRate field is the
 * file's true average bitrate, and it sits in `moov` within the first bytes
 * already flowing through this layer, so this adds no requests and no
 * playback latency. Emits at most once per open.
 */
internal class AlacCookieBitrateDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val isCandidate: (DataSpec) -> Boolean,
    private val mediaIdResolver: (DataSpec) -> String?,
    private val onCookie: (mediaId: String, bitrate: Int, sampleRate: Int) -> Unit,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        AlacCookieBitrateDataSource(
            upstream = upstreamFactory.createDataSource(),
            isCandidate = isCandidate,
            mediaIdResolver = mediaIdResolver,
            onCookie = onCookie,
        )
}

private class AlacCookieBitrateDataSource(
    private val upstream: DataSource,
    private val isCandidate: (DataSpec) -> Boolean,
    private val mediaIdResolver: (DataSpec) -> String?,
    private val onCookie: (mediaId: String, bitrate: Int, sampleRate: Int) -> Unit,
) : DataSource {
    private var activeDataSpec: DataSpec? = null
    private var sniffing = false
    private var done = false
    private var scratch = ByteArray(0)

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        activeDataSpec = dataSpec
        sniffing = isCandidate(dataSpec)
        done = false
        scratch = ByteArray(0)
        return upstream.open(dataSpec)
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val read = upstream.read(buffer, offset, length)
        if (read <= 0 || done || !sniffing) return read

        val dataSpec = activeDataSpec ?: return read
        if (scratch.size + read > MAX_SNIFF_BYTES) {
            done = true
            scratch = ByteArray(0)
            return read
        }
        scratch = scratch.copyOf(scratch.size + read)
        buffer.copyInto(scratch, scratch.size - read, offset, offset + read)

        val cookie = findAlacCookie(scratch)
        if (cookie != null) {
            done = true
            scratch = ByteArray(0)
            mediaIdResolver(dataSpec)
                ?.takeIf { it.isNotBlank() }
                ?.let { onCookie(it, cookie.bitrate, cookie.sampleRate) }
        }
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        done = false
        sniffing = false
        activeDataSpec = null
        scratch = ByteArray(0)
        upstream.close()
    }

    private data class AlacCookie(
        val bitrate: Int,
        val sampleRate: Int,
    )

    /**
     * Scans for an `alac` extension box and validates the full magic cookie.
     * Full validation matters: the enclosing `alac` sample entry also matches
     * the tag but fails the cookie field checks (bit depth 0, vendor bytes as
     * frame length), so entry-level false positives are rejected.
     */
    private fun findAlacCookie(bytes: ByteArray): AlacCookie? {
        if (bytes.size < MIN_COOKIE_WINDOW) return null
        var index = 0
        while (index <= bytes.size - MIN_COOKIE_WINDOW) {
            if (bytes[index] == 'a'.code.toByte() &&
                bytes[index + 1] == 'l'.code.toByte() &&
                bytes[index + 2] == 'a'.code.toByte() &&
                bytes[index + 3] == 'c'.code.toByte()
            ) {
                parseAlacCookie(bytes, index)?.let { return it }
            }
            index++
        }
        return null
    }

    private fun parseAlacCookie(bytes: ByteArray, tagIndex: Int): AlacCookie? {
        if (tagIndex < BOX_SIZE_OFFSET || tagIndex + COOKIE_LENGTH > bytes.size) return null
        val boxSize = u32(bytes, tagIndex - BOX_SIZE_OFFSET)
        if (boxSize < COOKIE_LENGTH || boxSize > MAX_BOX_SIZE) return null
        if (u32(bytes, tagIndex + 4) != 0L) return null
        val frameLength = u32(bytes, tagIndex + 8)
        if (frameLength == 0L || frameLength > MAX_FRAME_LENGTH) return null
        val bitDepth = bytes[tagIndex + 13].toInt() and 0xff
        if (bitDepth != 8 && bitDepth != 16 && bitDepth != 20 && bitDepth != 24 && bitDepth != 32) return null
        val channels = bytes[tagIndex + 17].toInt() and 0xff
        if (channels !in 1..8) return null
        val maxFrameBytes = u32(bytes, tagIndex + 20)
        if (maxFrameBytes == 0L || maxFrameBytes > MAX_FRAME_BYTES) return null
        val avgBitrate = u32(bytes, tagIndex + 24)
        if (avgBitrate < MIN_BITRATE || avgBitrate > MAX_BITRATE) return null
        val sampleRate = u32(bytes, tagIndex + 28)
        if (sampleRate < MIN_SAMPLE_RATE || sampleRate > MAX_SAMPLE_RATE) return null
        return AlacCookie(bitrate = avgBitrate.toInt(), sampleRate = sampleRate.toInt())
    }

    private fun u32(bytes: ByteArray, index: Int): Long =
        ((bytes[index].toLong() and 0xff) shl 24) or
            ((bytes[index + 1].toLong() and 0xff) shl 16) or
            ((bytes[index + 2].toLong() and 0xff) shl 8) or
            (bytes[index + 3].toLong() and 0xff)

    private companion object {
        private const val BOX_SIZE_OFFSET = 4
        private const val COOKIE_LENGTH = 36
        private const val MIN_COOKIE_WINDOW = COOKIE_LENGTH
        private const val MAX_BOX_SIZE = 128L
        private const val MAX_FRAME_LENGTH = 65_535L
        private const val MAX_FRAME_BYTES = 32L * 1024L * 1024L
        private const val MIN_BITRATE = 32_000L
        private const val MAX_BITRATE = 20_000_000L
        private const val MIN_SAMPLE_RATE = 8_000L
        private const val MAX_SAMPLE_RATE = 384_000L
        private const val MAX_SNIFF_BYTES = 384 * 1024
    }
}
