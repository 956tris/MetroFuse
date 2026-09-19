/** Metrolist Project (C) 2026 Licensed under GPL-3.0 | See git history for contributors */

package com.metrolist.music.utils.mix

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Prefetch-window intro sniffer for the Automix DJ engine.
 *
 * The engine cannot see the NEXT track's intro structure before it plays, so during the
 * minutes-long prefetch window a single caller range-downloads the head of the next track's
 * stream (~2MB cap), decodes ~12s with [MediaCodec], runs onset analysis, and stores the
 * intro grid ([IntroSniff]) for the upcoming blend.
 *
 * Battery / performance rules (callers must honor these):
 * - Single caller at a time is expected; the service serializes sniffs via one Job.
 * - At most ~2MB of network per sniff ([maxBytes]); the body is capped while streaming.
 * - The temp file is always deleted in a finally block.
 * - The codec is always stopped/released and the extractor always released (try/finally).
 * - Decode loops are bounded ([MAX_DECODE_ITERATIONS]) as a hang guard.
 * - No work ever runs on Main: both entry points enforce [Dispatchers.IO] via withContext.
 * - Per-hop work reuses one 1024-sample [FloatArray] plus one FFT workspace allocated once
 *   per sniff call; no per-hop allocations in the decode loop.
 */
object AutomixIntroSniffer {
    /** Intro grid for one prefetched track head. Confidence is 0..1. */
    data class IntroSniff(
        val mediaId: String,
        val bpmHintUsed: Float?,
        val firstDownbeatMs: Long?,
        val introEndMs: Long?,
        val introEnergy: Float,
        val confidence: Float,
    )

    private val httpClient = OkHttpClient()

    /**
     * Range-downloads the head of [streamUri] and sniffs its intro. Local files are NOT
     * handled here; use [sniffLocal] for those. Never throws: all failures return null.
     */
    suspend fun sniff(
        streamUri: String,
        mediaId: String,
        bpmHint: Float?,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Long = 2_000_000L,
        maxSeconds: Float = 12f,
        cacheDir: File,
    ): IntroSniff? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (streamUri.isBlank() || mediaId.isBlank()) return@runCatching null
                val scheme = streamUri.substringBefore("://", "").lowercase()
                if (scheme != "http" && scheme != "https") return@runCatching null

                val tmp = downloadHeadToTemp(streamUri, headers, maxBytes, cacheDir) ?: return@runCatching null
                try {
                    val decoded = decodeFileHead(tmp.absolutePath, maxSeconds) ?: return@runCatching null
                    buildSniff(mediaId, bpmHint, decoded)
                } finally {
                    try {
                        tmp.delete()
                    } catch (_: Exception) {
                    }
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Timber.tag("Automix").d(error, "Automix intro sniff failed for $mediaId")
            }.getOrNull()
        }

    /**
     * Same decode + analysis as [sniff] but for a local/content URI with no download.
     * Never throws: all failures return null.
     */
    suspend fun sniffLocal(
        context: Context,
        uriString: String,
        mediaId: String,
        bpmHint: Float?,
        maxSeconds: Float = 12f,
    ): IntroSniff? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (uriString.isBlank() || mediaId.isBlank()) return@runCatching null
                val decoded = decodeUriHead(context, uriString, maxSeconds) ?: return@runCatching null
                buildSniff(mediaId, bpmHint, decoded)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                Timber.tag("Automix").d(error, "Automix local intro sniff failed for $mediaId")
            }.getOrNull()
        }

    private fun downloadHeadToTemp(
        streamUri: String,
        headers: Map<String, String>,
        maxBytes: Long,
        cacheDir: File,
    ): File? {
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs()
        } catch (_: Exception) {
            return null
        }
        if (!cacheDir.isDirectory) return null
        val cappedBytes = maxBytes.coerceIn(64_000L, 8_000_000L)

        val builder =
            Request
                .Builder()
                .url(streamUri)
                .get()
        for ((key, value) in headers) {
            if (key.isBlank()) continue
            try {
                builder.addHeader(key.trim(), value)
            } catch (_: Exception) {
            }
        }
        builder.header("Range", "bytes=0-${cappedBytes - 1}")
        val request = builder.build()

        httpClient.newCall(request).execute().use { response ->
            if (response.code != 200 && response.code != 206) {
                Timber.tag("Automix").d("Automix sniff download rejected: HTTP ${response.code}")
                return null
            }
            val body = response.body ?: return null
            val tmp = File.createTempFile("automix_sniff", ".bin", cacheDir)
            try {
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val chunk = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(chunk)
                            if (read < 0) break
                            val remaining = cappedBytes - total
                            if (remaining <= 0L) break
                            val toWrite = minOf(read.toLong(), remaining).toInt()
                            output.write(chunk, 0, toWrite)
                            total += toWrite
                            if (total >= cappedBytes) break
                        }
                    }
                }
                if (tmp.length() <= 0L) {
                    try {
                        tmp.delete()
                    } catch (_: Exception) {
                    }
                    return null
                }
                return tmp
            } catch (error: Exception) {
                try {
                    tmp.delete()
                } catch (_: Exception) {
                }
                throw error
            }
        }
    }

    private fun decodeFileHead(
        tempPath: String,
        maxSeconds: Float,
    ): DecodedHead? {
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(tempPath)
            } catch (_: Exception) {
                return null
            }
            val format = selectFirstAudioTrack(extractor) ?: return null
            if (isTooShort(format)) return null
            return decodeSelectedExtractor(extractor, format, maxSeconds)
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun decodeUriHead(
        context: Context,
        uriString: String,
        maxSeconds: Float,
    ): DecodedHead? {
        val extractor = MediaExtractor()
        try {
            try {
                extractor.setDataSource(context, Uri.parse(uriString), null)
            } catch (_: Exception) {
                return null
            }
            val format = selectFirstAudioTrack(extractor) ?: return null
            if (isTooShort(format)) return null
            return decodeSelectedExtractor(extractor, format, maxSeconds)
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun selectFirstAudioTrack(extractor: MediaExtractor): MediaFormat? {
        val count =
            try {
                extractor.trackCount
            } catch (_: Exception) {
                return null
            }
        for (index in 0 until count) {
            val format =
                try {
                    extractor.getTrackFormat(index)
                } catch (_: Exception) {
                    continue
                }
            val mime =
                try {
                    format.getString(MediaFormat.KEY_MIME)
                } catch (_: Exception) {
                    null
                }
            if (mime == null || !mime.startsWith("audio/")) continue
            try {
                extractor.selectTrack(index)
            } catch (_: Exception) {
                return null
            }
            return format
        }
        return null
    }

    private fun isTooShort(format: MediaFormat): Boolean {
        if (!format.containsKey(MediaFormat.KEY_DURATION)) return false
        val durationUs =
            try {
                format.getLong(MediaFormat.KEY_DURATION)
            } catch (_: Exception) {
                return false
            }
        return durationUs in 1L until 3_000_000L
    }

    private fun decodeSelectedExtractor(
        extractor: MediaExtractor,
        format: MediaFormat,
        maxSeconds: Float,
    ): DecodedHead? {
        val mime =
            try {
                format.getString(MediaFormat.KEY_MIME)
            } catch (_: Exception) {
                null
            } ?: return null
        if (!mime.startsWith("audio/")) return null

        var channelCount =
            try {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } catch (_: Exception) {
                2
            }
        var sampleRate =
            try {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } catch (_: Exception) {
                44_100
            }
        if (sampleRate <= 0) sampleRate = 44_100
        if (channelCount <= 0) channelCount = 1

        val capSeconds = maxSeconds.coerceIn(3f, 20f)
        // Reused once per sniff call: mono hop accumulator + radix-2 FFT workspace.
        pendingHopFill = 0
        val hop = FloatArray(HOP_SIZE)
        val fftReal = DoubleArray(HOP_SIZE)
        val fftImag = DoubleArray(HOP_SIZE)
        val prevMag = FloatArray(FFT_BINS)
        val rmsPerHop = ArrayList<Float>(768)
        val fluxPerHop = ArrayList<Float>(768)
        // Single reused 256KB read buffer for extractor sample data.
        val readBuffer = ByteBuffer.allocate(256 * 1024)
        val info = MediaCodec.BufferInfo()
        var prevMagValid = false
        var decodedMaxTimeUs = 0L

        val codec =
            try {
                MediaCodec.createDecoderByType(mime)
            } catch (_: Exception) {
                return null
            }
        try {
            try {
                codec.configure(format, null, null, 0)
            } catch (_: Exception) {
                return null
            }
            try {
                codec.start()
            } catch (_: Exception) {
                return null
            }

            var extractorDone = false
            var decoderDone = false
            var iterations = 0
            while (!decoderDone && iterations < MAX_DECODE_ITERATIONS) {
                iterations++

                if (!extractorDone) {
                    val inputIndex =
                        try {
                            codec.dequeueInputBuffer(10_000)
                        } catch (_: Exception) {
                            break
                        }
                    if (inputIndex >= 0) {
                        val codecInput =
                            try {
                                codec.getInputBuffer(inputIndex)
                            } catch (_: Exception) {
                                null
                            }
                        if (codecInput == null) {
                            try {
                                extractor.advance()
                            } catch (_: Exception) {
                                extractorDone = true
                            }
                        } else {
                            readBuffer.clear()
                            val sampleSize =
                                try {
                                    extractor.readSampleData(readBuffer, 0)
                                } catch (_: Exception) {
                                    -1
                                }
                            if (sampleSize < 0) {
                                try {
                                    codec.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        0,
                                        0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                    )
                                } catch (_: Exception) {
                                    break
                                }
                                extractorDone = true
                            } else {
                                val sampleTime =
                                    try {
                                        extractor.getSampleTime()
                                    } catch (_: Exception) {
                                        0L
                                    }
                                if (sampleTime / 1_000_000.0 >= capSeconds) {
                                    try {
                                        codec.queueInputBuffer(
                                            inputIndex,
                                            0,
                                            0,
                                            0L,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                        )
                                    } catch (_: Exception) {
                                        break
                                    }
                                    extractorDone = true
                                } else {
                                    if (sampleSize > codecInput.remaining()) {
                                        try {
                                            extractor.advance()
                                        } catch (_: Exception) {
                                            extractorDone = true
                                        }
                                        try {
                                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, 0)
                                        } catch (_: Exception) {
                                            break
                                        }
                                    } else {
                                        try {
                                            codecInput.clear()
                                            codecInput.put(readBuffer.array(), 0, sampleSize)
                                            codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0)
                                        } catch (_: Exception) {
                                            break
                                        }
                                        try {
                                            extractor.advance()
                                        } catch (_: Exception) {
                                            extractorDone = true
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                val outputIndex =
                    try {
                        codec.dequeueOutputBuffer(info, 10_000)
                    } catch (_: Exception) {
                        break
                    }
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (extractorDone) {
                            // No more input and no output progress; the hang guard bounds this.
                        }
                        continue
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat =
                            try {
                                codec.outputFormat
                            } catch (_: Exception) {
                                null
                            }
                        if (outFormat != null) {
                            if (isFloatPcm(outFormat)) return null
                            try {
                                if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                                    outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE).takeIf { it > 0 }?.let {
                                        sampleRate = it
                                    }
                                }
                            } catch (_: Exception) {
                            }
                            try {
                                if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                                    outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).takeIf { it > 0 }?.let {
                                        channelCount = it
                                    }
                                }
                            } catch (_: Exception) {
                            }
                        }
                        continue
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> continue
                    outputIndex >= 0 -> {
                        val isCodecConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (!isCodecConfig && info.size > 0) {
                            val outBuffer =
                                try {
                                    codec.getOutputBuffer(outputIndex)
                                } catch (_: Exception) {
                                    null
                                }
                            if (outBuffer != null) {
                                // Lazily confirm PCM16 on the first real frame.
                                if (rmsPerHop.isEmpty() && fluxPerHop.isEmpty()) {
                                    val currentOut =
                                        try {
                                            codec.outputFormat
                                        } catch (_: Exception) {
                                            null
                                        }
                                    if (currentOut != null && isFloatPcm(currentOut)) {
                                        try {
                                            codec.releaseOutputBuffer(outputIndex, false)
                                        } catch (_: Exception) {
                                        }
                                        return null
                                    }
                                }
                                val consumed =
                                    consumePcm16ToHops(
                                        outBuffer,
                                        info,
                                        channelCount,
                                        hop,
                                        rmsPerHop,
                                        fluxPerHop,
                                        fftReal,
                                        fftImag,
                                        prevMag,
                                        prevMagValid,
                                    )
                                if (consumed) prevMagValid = true
                                if (info.presentationTimeUs > decodedMaxTimeUs) {
                                    decodedMaxTimeUs = info.presentationTimeUs
                                }
                            }
                            if (info.presentationTimeUs / 1_000_000.0 >= capSeconds) {
                                try {
                                    codec.releaseOutputBuffer(outputIndex, false)
                                } catch (_: Exception) {
                                }
                                break
                            }
                        }
                        try {
                            codec.releaseOutputBuffer(outputIndex, false)
                        } catch (_: Exception) {
                        }
                        if (isEos) decoderDone = true
                    }
                }
            }
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            try {
                codec.release()
            } catch (_: Exception) {
            }
        }

        if (rmsPerHop.isEmpty() || fluxPerHop.isEmpty()) return null
        if (sampleRate <= 0) return null
        val hopRate = sampleRate / HOP_SIZE.toFloat()
        if (hopRate <= 0f) return null
        val decodedSeconds = rmsPerHop.size / hopRate
        if (decodedSeconds < 3f) return null
        return DecodedHead(
            sampleRate = sampleRate,
            hopRate = hopRate,
            rmsPerHop = rmsPerHop,
            fluxPerHop = fluxPerHop,
            decodedSeconds = decodedSeconds,
        )
    }

    // Staged mono tail that has not yet filled a full 1024-sample hop. Kept as a member
    // so consumePcm16ToHops stays allocation-free; only one sniff runs at a time.
    private var pendingHopFill: Int = 0

    /**
     * Reads one decoded PCM16 frame into the mono [hop] accumulator. Returns true when at
     * least one full hop was completed (so the caller can mark the flux history valid).
     * Assumes 16-bit little-endian interleaved output; float PCM is rejected by the caller.
     */
    private fun consumePcm16ToHops(
        outBuffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channelCount: Int,
        hop: FloatArray,
        rmsPerHop: ArrayList<Float>,
        fluxPerHop: ArrayList<Float>,
        fftReal: DoubleArray,
        fftImag: DoubleArray,
        prevMag: FloatArray,
        prevMagValid: Boolean,
    ): Boolean {
        val channels = channelCount.coerceIn(1, 8)
        val start = info.offset.coerceAtLeast(0)
        val end = (info.offset + info.size).coerceAtMost(outBuffer.capacity()).coerceAtLeast(start)
        if (end - start < 2) return false
        val dup = outBuffer.duplicate()
        try {
            dup.order(ByteOrder.LITTLE_ENDIAN)
        } catch (_: Exception) {
        }
        dup.position(start)
        dup.limit(end)

        var madeHop = false
        var validHistory = prevMagValid
        var fill = pendingHopFill.coerceIn(0, HOP_SIZE)
        if (fill >= HOP_SIZE) fill = 0
        var channelIndex = 0
        var frameSum = 0f
        while (dup.remaining() >= 2) {
            val sample =
                try {
                    dup.short
                } catch (_: Exception) {
                    break
                }
            frameSum += sample / 32_768f
            channelIndex++
            if (channelIndex >= channels) {
                hop[fill] = frameSum / channels
                frameSum = 0f
                channelIndex = 0
                fill++
                if (fill >= HOP_SIZE) {
                    rmsPerHop.add(rmsOf(hop))
                    fluxPerHop.add(spectralFlux(hop, fftReal, fftImag, prevMag, validHistory))
                    validHistory = true
                    madeHop = true
                    fill = 0
                }
            }
        }
        pendingHopFill = fill
        return madeHop
    }

    private fun buildSniff(
        mediaId: String,
        bpmHint: Float?,
        decoded: DecodedHead,
    ): IntroSniff? {
        val hintValid = bpmHint != null && bpmHint in 60f..200f
        val estimated = estimateBpm(decoded.fluxPerHop, decoded.hopRate)
        val bpmUsed = if (hintValid) bpmHint else estimated

        val firstDownbeatMs = findFirstDownbeat(decoded.fluxPerHop, decoded.hopRate)
        val introEndMs = findIntroEnd(decoded.rmsPerHop, decoded.hopRate)
        val introEnergy = introEnergyOf(decoded.rmsPerHop, decoded.hopRate)

        val hasDownbeat = firstDownbeatMs != null
        val hasIntro = introEndMs != null
        val confidence =
            when {
                hasDownbeat && hasIntro -> 0.85f
                hasDownbeat || hasIntro -> 0.6f
                decoded.decodedSeconds >= 3f -> 0.35f
                else -> return null
            }
        return IntroSniff(
            mediaId = mediaId,
            bpmHintUsed = bpmUsed,
            firstDownbeatMs = firstDownbeatMs,
            introEndMs = introEndMs,
            introEnergy = introEnergy.coerceIn(0f, 1f),
            confidence = confidence,
        )
    }

    private fun estimateBpm(
        flux: List<Float>,
        hopRate: Float,
    ): Float? {
        if (flux.size < 48 || hopRate <= 0f) return null
        var mean = 0.0
        for (value in flux) mean += value
        mean /= flux.size
        val lagMin = ((60.0 / 200.0) * hopRate).toInt().coerceAtLeast(2)
        val lagMax = ((60.0 / 60.0) * hopRate).toInt().coerceAtLeast(lagMin + 1)
        if (lagMax + 8 >= flux.size) return null
        var bestLag = -1
        var bestScore = Double.NEGATIVE_INFINITY
        var lag = lagMin
        while (lag <= lagMax) {
            var score = 0.0
            var index = lag
            while (index < flux.size) {
                score += (flux[index] - mean) * (flux[index - lag] - mean)
                index++
            }
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
            lag++
        }
        if (bestLag <= 0 || bestScore <= 0.0) return null
        return ((60f * hopRate) / bestLag).coerceIn(60f, 200f)
    }

    private fun findFirstDownbeat(
        flux: List<Float>,
        hopRate: Float,
    ): Long? {
        if (flux.isEmpty() || hopRate <= 0f) return null
        var mean = 0f
        var max = Float.NEGATIVE_INFINITY
        for (value in flux) {
            mean += value
            if (value > max) max = value
        }
        mean /= flux.size
        var variance = 0f
        for (value in flux) {
            val delta = value - mean
            variance += delta * delta
        }
        val std = sqrt(variance / flux.size)
        val threshold = maxOf(mean + 0.75f * std, mean * 1.6f, max * 0.30f)
        if (!(threshold > 0f) || max <= 0f) return null
        val startHop = (0.25f * hopRate).toInt().coerceAtLeast(0)
        val hopDurSec = 1f / hopRate
        for (index in startHop until flux.size) {
            if (flux[index] > threshold) {
                return (index * hopDurSec * 1000f).toLong()
            }
        }
        return null
    }

    private fun findIntroEnd(
        rms: List<Float>,
        hopRate: Float,
    ): Long? {
        if (rms.isEmpty() || hopRate <= 0f) return null
        var overall = 0f
        for (value in rms) overall += value
        overall /= rms.size
        if (overall <= 1e-9f) return null
        val windowHops = (2f * hopRate).toInt().coerceAtLeast(1)
        val stepHops = hopRate.toInt().coerceAtLeast(1)
        val lastStart = rms.size - windowHops
        if (lastStart < 0) return null
        var start = 0
        while (start <= lastStart) {
            var sum = 0f
            var index = start
            val windowEnd = start + windowHops
            while (index < windowEnd) {
                sum += rms[index]
                index++
            }
            if (sum / windowHops > 0.6f * overall) {
                if (start == 0) return null
                return ((start / hopRate) * 1000f).toLong()
            }
            start += stepHops
        }
        return null
    }

    private fun introEnergyOf(
        rms: List<Float>,
        hopRate: Float,
    ): Float {
        if (rms.isEmpty() || hopRate <= 0f) return 0f
        var peak = 0f
        for (value in rms) if (value > peak) peak = value
        if (peak <= 1e-9f) return 0f
        val firstHops = (4f * hopRate).toInt().coerceIn(1, rms.size)
        var sum = 0f
        for (index in 0 until firstHops) sum += rms[index]
        return ((sum / firstHops) / peak).coerceIn(0f, 1f)
    }

    private fun rmsOf(hop: FloatArray): Float {
        var sum = 0.0
        for (index in hop.indices) {
            val sample = hop[index].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / hop.size).toFloat()
    }

    private fun spectralFlux(
        hop: FloatArray,
        real: DoubleArray,
        imag: DoubleArray,
    ): Float = spectralFlux(hop, real, imag, null, false)

    private fun spectralFlux(
        hop: FloatArray,
        real: DoubleArray,
        imag: DoubleArray,
        prevMag: FloatArray?,
        prevValid: Boolean,
    ): Float {
        val n = HOP_SIZE
        for (index in 0 until n) {
            val window = 0.5 * (1.0 - cos(2.0 * PI * index / (n - 1)))
            real[index] = hop[index] * window
            imag[index] = 0.0
        }
        fftInPlace(real, imag)
        var flux = 0f
        if (prevMag == null) {
            for (bin in 0 until FFT_BINS) {
                val magnitude = sqrt(real[bin] * real[bin] + imag[bin] * imag[bin]).toFloat()
                val positive = magnitude - 0f
                if (positive > 0f) flux += positive
            }
            return flux
        }
        for (bin in 0 until FFT_BINS) {
            val magnitude = sqrt(real[bin] * real[bin] + imag[bin] * imag[bin]).toFloat()
            if (prevValid) {
                val delta = magnitude - prevMag[bin]
                if (delta > 0f) flux += delta
            }
            prevMag[bin] = magnitude
        }
        return flux
    }

    private fun fftInPlace(
        real: DoubleArray,
        imag: DoubleArray,
    ) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tempReal = real[i]
                real[i] = real[j]
                real[j] = tempReal
                val tempImag = imag[i]
                imag[i] = imag[j]
                imag[j] = tempImag
            }
        }
        var length = 2
        while (length <= n) {
            val angle = -2.0 * PI / length
            val wLenReal = cos(angle)
            val wLenImag = sin(angle)
            var i = 0
            while (i < n) {
                var wReal = 1.0
                var wImag = 0.0
                for (k in 0 until length / 2) {
                    val upperReal = real[i + k]
                    val upperImag = imag[i + k]
                    val lowerReal = real[i + k + length / 2] * wReal - imag[i + k + length / 2] * wImag
                    val lowerImag = real[i + k + length / 2] * wImag + imag[i + k + length / 2] * wReal
                    real[i + k] = upperReal + lowerReal
                    imag[i + k] = upperImag + lowerImag
                    real[i + k + length / 2] = upperReal - lowerReal
                    imag[i + k + length / 2] = upperImag - lowerImag
                    val nextWReal = wReal * wLenReal - wImag * wLenImag
                    wImag = wReal * wLenImag + wImag * wLenReal
                    wReal = nextWReal
                }
                i += length
            }
            length = length shl 1
        }
    }

    private fun isFloatPcm(format: MediaFormat): Boolean {
        if (!format.containsKey(MediaFormat.KEY_PCM_ENCODING)) return false
        return try {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
        } catch (_: Exception) {
            false
        }
    }

    private data class DecodedHead(
        val sampleRate: Int,
        val hopRate: Float,
        val rmsPerHop: ArrayList<Float>,
        val fluxPerHop: ArrayList<Float>,
        val decodedSeconds: Float,
    )

    private const val HOP_SIZE = 1024
    private const val FFT_BINS = 512
    private const val MAX_DECODE_ITERATIONS = 20_000
}
