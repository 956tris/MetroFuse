/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlin.math.min

internal class LiveFlacBitrateDataSourceFactory(
    private val upstreamFactory: DataSource.Factory,
    private val isEnabled: () -> Boolean,
    private val mediaIdResolver: (DataSpec) -> String?,
    private val isFlacCandidate: (DataSpec, String?) -> Boolean,
    private val sampleRateProvider: (String?) -> Int?,
    private val onBitrate: (String, Int, Long?, Long?) -> Unit,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        LiveFlacBitrateDataSource(
            upstream = upstreamFactory.createDataSource(),
            isEnabled = isEnabled,
            mediaIdResolver = mediaIdResolver,
            isFlacCandidate = isFlacCandidate,
            sampleRateProvider = sampleRateProvider,
            onBitrate = onBitrate,
        )
}

private class LiveFlacBitrateDataSource(
    private val upstream: DataSource,
    private val isEnabled: () -> Boolean,
    private val mediaIdResolver: (DataSpec) -> String?,
    private val isFlacCandidate: (DataSpec, String?) -> Boolean,
    private val sampleRateProvider: (String?) -> Int?,
    private val onBitrate: (String, Int, Long?, Long?) -> Unit,
) : DataSource {
    private var activeDataSpec: DataSpec? = null
    private var activeMediaId: String? = null
    private var parser: FlacFrameBitrateParser? = null

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        activeDataSpec = dataSpec
        activeMediaId = mediaIdResolver(dataSpec)
        parser = null
        return upstream.open(dataSpec)
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val read = upstream.read(buffer, offset, length)
        if (read <= 0) return read

        val dataSpec = activeDataSpec
        if (!isEnabled() || dataSpec == null) {
            parser = null
            return read
        }

        val mediaId = activeMediaId ?: mediaIdResolver(dataSpec).also { activeMediaId = it }
        if (!isFlacCandidate(dataSpec, mediaId)) return read

        val activeParser =
            parser ?: FlacFrameBitrateParser(
                fallbackSampleRate = { sampleRateProvider(mediaId) },
                onFrameBitrate = { sample ->
                    mediaId
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            onBitrate(
                                it,
                                sample.bitrate,
                                sample.mediaStartMs,
                                sample.mediaEndMs,
                            )
                        }
                },
            ).also { parser = it }

        activeParser.feed(buffer, offset, read)
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        parser = null
        activeDataSpec = null
        activeMediaId = null
        upstream.close()
    }
}

private class FlacFrameBitrateParser(
    private val fallbackSampleRate: () -> Int?,
    private val onFrameBitrate: (FlacFrameBitrateSample) -> Unit,
) {
    private var buffer = ByteArray(64 * 1024)
    private var start = 0
    private var end = 0
    private var foundFlacMarker = false
    private var metadataComplete = false
    private var streamSampleRate: Int? = null
    private var streamChannels: Int? = null
    private var streamBitsPerSample: Int? = null
    private var mediaPositionSamples = 0L

    fun feed(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        if (length <= 0) return
        var consumed = 0
        while (consumed < length) {
            val chunk = min(MAX_FEED_CHUNK_BYTES, length - consumed)
            append(bytes, offset + consumed, chunk)
            parse()
            compact()
            consumed += chunk
        }
    }

    private fun append(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) {
        ensureCapacity(length)
        bytes.copyInto(buffer, end, offset, offset + length)
        end += length
    }

    private fun ensureCapacity(extra: Int) {
        val readable = readable()
        if (buffer.size - end >= extra) return
        if (start > 0) compact(force = true)
        if (buffer.size - end >= extra) return

        var nextSize = buffer.size
        while (nextSize - end < extra) nextSize *= 2
        buffer = buffer.copyOf(nextSize.coerceAtMost(MAX_BUFFER_BYTES))
        if (buffer.size - end < extra) {
            discard((readable() - SYNC_TAIL_BYTES).coerceAtLeast(0))
            compact(force = true)
        }
    }

    private fun parse() {
        if (!metadataComplete) parseMetadata()
        if (metadataComplete) parseFrames()
    }

    private fun parseMetadata() {
        if (!foundFlacMarker) {
            val markerIndex = indexOfFlacMarker()
            if (markerIndex >= 0) {
                discard(markerIndex + FLAC_MARKER.size)
                foundFlacMarker = true
            } else {
                val syncIndex = findNextFrameHeader(0)
                if (syncIndex >= 0) {
                    discard(syncIndex)
                    metadataComplete = true
                    return
                }
                if (readable() > FLAC_MARKER.size) {
                    discard(readable() - FLAC_MARKER.size)
                }
                return
            }
        }

        while (readable() >= 4) {
            val header = byteAt(0)
            val blockType = header and 0x7f
            val isLast = (header and 0x80) != 0
            val length = (byteAt(1) shl 16) or (byteAt(2) shl 8) or byteAt(3)
            if (length < 0 || length > MAX_METADATA_BLOCK_BYTES) {
                metadataComplete = true
                return
            }
            if (readable() < 4 + length) return

            if (blockType == STREAMINFO_BLOCK_TYPE && length >= STREAMINFO_LENGTH) {
                parseStreamInfo(offset = 4)
            }
            discard(4 + length)
            if (isLast) {
                metadataComplete = true
                return
            }
        }
    }

    private fun parseStreamInfo(offset: Int) {
        streamSampleRate =
            (
                (byteAt(offset + 10) shl 12) or
                    (byteAt(offset + 11) shl 4) or
                    (byteAt(offset + 12) shr 4)
            ).takeIf { it > 0 }
        streamChannels = (((byteAt(offset + 12) and 0x0e) shr 1) + 1).takeIf { it in 1..8 }
        streamBitsPerSample =
            (
                ((byteAt(offset + 12) and 0x01) shl 4) or
                    ((byteAt(offset + 13) and 0xf0) shr 4)
            ).plus(1).takeIf { it in 4..32 }
    }

    private fun parseFrames() {
        while (readable() >= MIN_FRAME_HEADER_BYTES) {
            val syncIndex = findNextFrameHeader(0)
            if (syncIndex < 0) {
                if (readable() > MAX_FRAME_SCAN_BYTES) discard(readable() - SYNC_TAIL_BYTES)
                return
            }
            if (syncIndex > 0) {
                discard(syncIndex)
                continue
            }

            val currentHeader = parseFrameHeader(0)
            if (currentHeader == null) {
                discard(1)
                continue
            }

            val nextFrameIndex = findNextValidFrameHeader(currentHeader.headerEnd)
            if (nextFrameIndex < 0) {
                if (readable() > MAX_BUFFER_BYTES) {
                    discard(min(readable() - SYNC_TAIL_BYTES, currentHeader.headerEnd.coerceAtLeast(1)))
                }
                return
            }

            val frameBytes = nextFrameIndex
            val hasAbsoluteMediaPosition = foundFlacMarker && streamSampleRate != null
            val mediaStartMs =
                if (hasAbsoluteMediaPosition) {
                    (mediaPositionSamples * 1_000L) / currentHeader.sampleRate
                } else {
                    null
                }
            val rawBitrate =
                (frameBytes.toLong() * 8L * currentHeader.sampleRate) / currentHeader.blockSamples
            if (rawBitrate < MIN_BITRATE || rawBitrate > currentHeader.maxPlausibleBitrate()) {
                discard(nextFrameIndex)
                continue
            }
            mediaPositionSamples += currentHeader.blockSamples
            val mediaEndMs =
                mediaStartMs?.let { (mediaPositionSamples * 1_000L) / currentHeader.sampleRate }
            val bitrate = rawBitrate.toInt()
            onFrameBitrate(
                FlacFrameBitrateSample(
                    bitrate = bitrate,
                    mediaStartMs = mediaStartMs,
                    mediaEndMs = mediaEndMs,
                ),
            )
            discard(nextFrameIndex)
        }
    }

    private fun parseFrameHeader(index: Int): FrameHeader? {
        if (readable() - index < MIN_FRAME_HEADER_BYTES) return null
        if (!looksLikeSync(index)) return null

        val blockSizeCode = byteAt(index + 2) shr 4
        val sampleRateCode = byteAt(index + 2) and 0x0f
        val channelAssignment = byteAt(index + 3) shr 4
        val sampleSizeCode = (byteAt(index + 3) shr 1) and 0x07
        val reservedBit = byteAt(index + 3) and 0x01
        if (
            blockSizeCode == 0 ||
            sampleRateCode == 15 ||
            channelAssignment > 10 ||
            sampleSizeCode == 3 ||
            sampleSizeCode == 7 ||
            reservedBit != 0
        ) {
            return null
        }

        val utfLength = utf8IntegerLength(byteAt(index + 4)) ?: return null
        var cursor = index + 4 + utfLength
        if (readable() < cursor + 1) return null

        val blockSamples =
            when (blockSizeCode) {
                1 -> 192
                in 2..5 -> 576 shl (blockSizeCode - 2)
                6 -> {
                    if (readable() <= cursor) return null
                    byteAt(cursor++) + 1
                }
                7 -> {
                    if (readable() < cursor + 2) return null
                    ((byteAt(cursor) shl 8) or byteAt(cursor + 1)).also { cursor += 2 } + 1
                }
                else -> 256 shl (blockSizeCode - 8)
            }.takeIf { it > 0 } ?: return null

        val sampleRate =
            when (sampleRateCode) {
                0 -> streamSampleRate ?: fallbackSampleRate()
                1 -> 88_200
                2 -> 176_400
                3 -> 192_000
                4 -> 8_000
                5 -> 16_000
                6 -> 22_050
                7 -> 24_000
                8 -> 32_000
                9 -> 44_100
                10 -> 48_000
                11 -> 96_000
                12 -> {
                    if (readable() <= cursor) return null
                    byteAt(cursor++) * 1_000
                }
                13 -> {
                    if (readable() < cursor + 2) return null
                    ((byteAt(cursor) shl 8) or byteAt(cursor + 1)).also { cursor += 2 }
                }
                14 -> {
                    if (readable() < cursor + 2) return null
                    (((byteAt(cursor) shl 8) or byteAt(cursor + 1)) * 10).also { cursor += 2 }
                }
                else -> null
            }?.takeIf { it in 8_000..384_000 } ?: return null

        val channels =
            when (channelAssignment) {
                in 0..7 -> channelAssignment + 1
                in 8..10 -> 2
                else -> null
            } ?: streamChannels ?: DEFAULT_CHANNELS
        val bitsPerSample =
            when (sampleSizeCode) {
                0 -> streamBitsPerSample ?: DEFAULT_BITS_PER_SAMPLE
                1 -> 8
                2 -> 12
                4 -> 16
                5 -> 20
                6 -> 24
                else -> null
            } ?: return null

        if (readable() <= cursor) return null
        return FrameHeader(
            headerEnd = cursor + 1,
            blockSamples = blockSamples,
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
        )
    }

    private fun findNextValidFrameHeader(from: Int): Int {
        var index = from.coerceAtLeast(1)
        while (index <= readable() - MIN_FRAME_HEADER_BYTES) {
            if (parseFrameHeader(index) != null) return index
            index++
        }
        return -1
    }

    private fun findNextFrameHeader(from: Int): Int {
        var index = from.coerceAtLeast(0)
        while (index <= readable() - 2) {
            if (looksLikeSync(index)) return index
            index++
        }
        return -1
    }

    private fun looksLikeSync(index: Int): Boolean =
        readable() - index >= 2 &&
            byteAt(index) == 0xff &&
            (byteAt(index + 1) and 0xfe) == 0xf8

    private fun utf8IntegerLength(first: Int): Int? =
        when {
            (first and 0x80) == 0 -> 1
            (first and 0xe0) == 0xc0 -> 2
            (first and 0xf0) == 0xe0 -> 3
            (first and 0xf8) == 0xf0 -> 4
            (first and 0xfc) == 0xf8 -> 5
            (first and 0xfe) == 0xfc -> 6
            first == 0xfe -> 7
            else -> null
        }

    private fun indexOfFlacMarker(): Int {
        for (index in 0..readable() - FLAC_MARKER.size) {
            var matches = true
            for (markerIndex in FLAC_MARKER.indices) {
                if (byteAt(index + markerIndex) != FLAC_MARKER[markerIndex].toInt()) {
                    matches = false
                    break
                }
            }
            if (matches) return index
        }
        return -1
    }

    private fun byteAt(index: Int): Int = buffer[start + index].toInt() and 0xff

    private fun readable(): Int = end - start

    private fun discard(count: Int) {
        start = (start + count).coerceAtMost(end)
    }

    private fun compact(force: Boolean = false) {
        if (!force && start < buffer.size / 2 && readable() <= MAX_BUFFER_BYTES) return
        val readable = readable().coerceAtMost(MAX_BUFFER_BYTES)
        if (readable > 0) buffer.copyInto(buffer, 0, end - readable, end)
        start = 0
        end = readable
    }

    private data class FrameHeader(
        val headerEnd: Int,
        val blockSamples: Int,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
    )

    private fun FrameHeader.maxPlausibleBitrate(): Long {
        val pcmBitrate = sampleRate.toLong() * channels * bitsPerSample
        val sampleRateCap =
            when {
                sampleRate <= 48_000 -> 3_000_000L
                sampleRate <= 96_000 -> 6_500_000L
                sampleRate <= 192_000 -> 12_000_000L
                else -> MAX_BITRATE.toLong()
            }
        return (pcmBitrate * 13L / 10L)
            .coerceAtLeast(768_000L)
            .coerceAtMost(sampleRateCap)
    }

    data class FlacFrameBitrateSample(
        val bitrate: Int,
        val mediaStartMs: Long?,
        val mediaEndMs: Long?,
    )

    private companion object {
        private val FLAC_MARKER = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte())
        private const val STREAMINFO_BLOCK_TYPE = 0
        private const val STREAMINFO_LENGTH = 34
        private const val MIN_FRAME_HEADER_BYTES = 6
        private const val SYNC_TAIL_BYTES = 4
        private const val MAX_METADATA_BLOCK_BYTES = 16 * 1024 * 1024
        private const val MAX_FRAME_SCAN_BYTES = 192 * 1024
        private const val MAX_BUFFER_BYTES = 512 * 1024
        private const val MAX_FEED_CHUNK_BYTES = 64 * 1024
        private const val MIN_BITRATE = 32_000
        private const val MAX_BITRATE = 20_000_000
        private const val DEFAULT_CHANNELS = 2
        private const val DEFAULT_BITS_PER_SAMPLE = 16
    }
}
