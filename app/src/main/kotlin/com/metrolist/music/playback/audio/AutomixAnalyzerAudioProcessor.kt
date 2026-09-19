/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Passive PCM tap that learns per-track energy, beat phase and downbeat alignment for Automix.
 *
 * Seeks are handled crudely: ExoPlayer flushes on seek, so [flush] drops the partial hop and
 * re-anchors the beat phase to the current position instead of rebuilding absolute timing.
 */
@UnstableApi
@Suppress("DEPRECATION")
class AutomixAnalyzerAudioProcessor : AudioProcessor {

    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID

    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false

    @Volatile
    var analysisEnabled: Boolean = false

    @Volatile
    private var activeMediaId: String? = null

    @Volatile
    private var activeBpmHint: Float? = null

    @Volatile
    private var trackDurationMs: Long = 0L

    private val hopBuffer = FloatArray(HOP_SIZE)
    private var hopFill = 0

    private val fftReal = DoubleArray(HOP_SIZE)
    private val fftImag = DoubleArray(HOP_SIZE)
    private val prevLogMag = DoubleArray(HOP_SIZE / 2 + 1)

    private var fluxCount = 0L
    private var fluxMean = 0.0
    private var fluxM2 = 0.0

    private var hopsObserved = 0
    private var trackSeconds = 0.0
    private var beatPeriodSec = 0.0
    private var nextBeatTime = 0.0
    private var beatGridIndex = 0
    private var beatHits = 0
    private var beatMisses = 0
    private var lastBeatFlux = 0.0
    private var firstBeatRecorded = false

    private val beatSub = DoubleArray(MAX_BEATS)
    private val beatFluxDelta = DoubleArray(MAX_BEATS)
    private val beatBroad = DoubleArray(MAX_BEATS)
    private var beatCount = 0
    private var maxSub = 0.0
    private var maxFluxDelta = 0.0
    private var minFluxDelta = 0.0
    private var maxBroad = 0.0

    private val phaseVotes = DoubleArray(4)

    private val energyPerSecondRaw = FloatArray(MAX_ENERGY_SECONDS)
    private var energySecondsCount = 0
    private var bucketEnergySum = 0.0
    private var bucketHopCount = 0
    private var bucketElapsed = 0.0

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding

        if (encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        return inputAudioFormat
    }

    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            outputBuffer = EMPTY_BUFFER
            return
        }

        if (analysisEnabled) {
            synchronized(this) {
                if (analysisEnabled) {
                    analyzeInput(inputBuffer)
                }
            }
        }

        val out = replaceOutputBuffer(inputBuffer.remaining())
        out.put(inputBuffer)
        out.flip()
    }

    @Synchronized
    fun setActiveTrack(mediaId: String?, bpmHint: Float?, durationMs: Long) {
        activeMediaId = mediaId
        activeBpmHint = bpmHint
        trackDurationMs = durationMs

        hopFill = 0
        hopsObserved = 0
        trackSeconds = 0.0

        fluxCount = 0L
        fluxMean = 0.0
        fluxM2 = 0.0
        prevLogMag.fill(0.0)

        beatHits = 0
        beatMisses = 0
        beatGridIndex = 0
        beatCount = 0
        lastBeatFlux = 0.0
        firstBeatRecorded = false
        maxSub = 0.0
        maxFluxDelta = 0.0
        minFluxDelta = 0.0
        maxBroad = 0.0
        phaseVotes.fill(0.0)

        energySecondsCount = 0
        bucketEnergySum = 0.0
        bucketHopCount = 0
        bucketElapsed = 0.0

        inputEnded = false

        val bpm = bpmHint
        if (bpm != null && bpm >= 60f && bpm <= 200f) {
            beatPeriodSec = 60.0 / bpm
            nextBeatTime = beatPeriodSec
        } else {
            beatPeriodSec = 0.0
            nextBeatTime = 0.0
        }
    }

    @Synchronized
    fun snapshot(): TrackLearnSnapshot? {
        val mediaId = activeMediaId ?: return null
        if (hopsObserved < MIN_SNAPSHOT_HOPS) return null

        val bpmLocal = activeBpmHint
        val periodMs: Float? =
            if (bpmLocal != null && bpmLocal >= 60f && bpmLocal <= 200f) 60000f / bpmLocal else null

        val subDenom = maxSub + EPS
        val broadDenom = maxBroad + EPS
        val fluxRange = maxFluxDelta - minFluxDelta
        val beats = ArrayList<BeatF>(beatCount)
        for (i in 0 until beatCount) {
            val sub01 = (beatSub[i] / subDenom).coerceIn(0.0, 1.0).toFloat()
            val broad01 = (beatBroad[i] / broadDenom).coerceIn(0.0, 1.0).toFloat()
            val flux01 = if (fluxRange > 1e-12) {
                ((beatFluxDelta[i] - minFluxDelta) / fluxRange).toFloat().coerceIn(0f, 1f)
            } else {
                0f
            }
            beats.add(BeatF(sub01, flux01, broad01))
        }

        var bestBin = 0
        for (b in 1..3) {
            if (phaseVotes[b] > phaseVotes[bestBin]) bestBin = b
        }
        var secondBest = 0.0
        for (b in 0..3) {
            if (b != bestBin && phaseVotes[b] > secondBest) secondBest = phaseVotes[b]
        }
        // Confidence blends vote margin with beat hit rate; meaningless before 16 beats.
        val margin = (phaseVotes[bestBin] - secondBest) / (phaseVotes[bestBin] + EPS)
        val totalBeats = beatHits + beatMisses
        val hitRate = if (totalBeats > 0) beatHits.toDouble() / totalBeats else 0.0
        var confidence = (0.6 * margin + 0.4 * hitRate).coerceIn(0.0, 1.0)
        if (beatCount < 16) confidence = 0.0

        val durationObservedMs = (trackSeconds * 1000.0).toLong()
        val targetMs = if (trackDurationMs > 0L) minOf(trackDurationMs, COMPLETE_CAP_MS) else COMPLETE_CAP_MS
        val complete = durationObservedMs >= targetMs || inputEnded

        return TrackLearnSnapshot(
            mediaId = mediaId,
            hopsObserved = hopsObserved,
            durationObservedMs = durationObservedMs,
            bpmHint = bpmLocal,
            beatHits = beatHits,
            beatMisses = beatMisses,
            beatPeriodMs = periodMs,
            downbeatBin = bestBin,
            downbeatConfidence = confidence.toFloat(),
            beats = beats,
            energyPerSecond = energyPerSecondRaw.copyOf(energySecondsCount),
            complete = complete,
        )
    }

    private fun analyzeInput(inputBuffer: ByteBuffer) {
        if (activeMediaId == null || sampleRate <= 0 || channelCount <= 0) return
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        val frameCount = inputBuffer.remaining() / 2 / channelCount
        if (frameCount <= 0) return
        val basePosition = inputBuffer.position()
        val channels = channelCount

        var frame = 0
        while (frame < frameCount) {
            var sum = 0
            var c = 0
            while (c < channels) {
                sum += inputBuffer.getShort(basePosition + (frame * channels + c) * 2).toInt()
                c++
            }
            hopBuffer[hopFill] = (sum.toFloat() / channels) / 32768f
            hopFill++
            if (hopFill >= HOP_SIZE) {
                hopFill = 0
                processHop()
            }
            frame++
        }
    }

    private fun processHop() {
        if (sampleRate <= 0) return
        for (i in 0 until HOP_SIZE) {
            fftReal[i] = hopBuffer[i].toDouble()
            fftImag[i] = 0.0
        }
        Radix2Fft.transform(fftReal, fftImag)

        val rate = sampleRate.toDouble()
        var sub = 0.0
        var subN = 0
        var mid = 0.0
        var midN = 0
        var high = 0.0
        var highN = 0
        var flux = 0.0
        var i = 1
        while (i <= HOP_SIZE / 2) {
            val magSq = fftReal[i] * fftReal[i] + fftImag[i] * fftImag[i]
            val hz = i * rate / HOP_SIZE
            if (hz >= 20.0 && hz < 150.0) {
                sub += magSq
                subN++
            } else if (hz >= 150.0 && hz < 4000.0) {
                mid += magSq
                midN++
            } else if (hz >= 4000.0 && hz < 20000.0) {
                high += magSq
                highN++
            }
            val logMag = ln(1.0 + sqrt(magSq))
            val delta = logMag - prevLogMag[i]
            if (delta > 0.0) flux += delta
            prevLogMag[i] = logMag
            i++
        }
        val subEnergy = if (subN > 0) sub / subN else 0.0
        val midEnergy = if (midN > 0) mid / midN else 0.0
        val highEnergy = if (highN > 0) high / highN else 0.0
        val broadband = subEnergy + midEnergy + highEnergy

        // Adaptive peak: flux above running mean + 1.5 sigma (Welford running stats).
        var isPeak = false
        if (fluxCount >= 8) {
            val variance = if (fluxCount > 1L) fluxM2 / (fluxCount - 1).toDouble() else 0.0
            if (flux > fluxMean + 1.5 * sqrt(maxOf(variance, 0.0))) isPeak = true
        }
        fluxCount++
        val meanDelta = flux - fluxMean
        fluxMean += meanDelta / fluxCount.toDouble()
        fluxM2 += meanDelta * (flux - fluxMean)

        val hopDuration = HOP_SIZE.toDouble() / rate
        trackSeconds += hopDuration
        hopsObserved++

        if (beatPeriodSec > 0.0) {
            updateBeatGrid(isPeak, subEnergy, flux, broadband)
        }

        bucketEnergySum += broadband
        bucketHopCount++
        bucketElapsed += hopDuration
        if (bucketElapsed >= 1.0) {
            if (energySecondsCount < MAX_ENERGY_SECONDS && bucketHopCount > 0) {
                energyPerSecondRaw[energySecondsCount] =
                    sqrt(bucketEnergySum / bucketHopCount).toFloat()
                energySecondsCount++
            }
            bucketEnergySum = 0.0
            bucketHopCount = 0
            bucketElapsed -= 1.0
        }
    }

    private fun updateBeatGrid(isPeak: Boolean, subEnergy: Double, flux: Double, broadband: Double) {
        val tolerance = 0.15 * beatPeriodSec
        val peakTime = trackSeconds
        if (isPeak) {
            var guard = 0
            while (peakTime > nextBeatTime + tolerance && guard < 8) {
                beatMisses++
                beatGridIndex++
                nextBeatTime += beatPeriodSec
                guard++
            }
            if (abs(peakTime - nextBeatTime) <= tolerance) {
                beatHits++
                recordBeat(subEnergy, flux, broadband)
                nextBeatTime = peakTime + beatPeriodSec
                beatGridIndex++
            }
        } else {
            var guard = 0
            while (peakTime > nextBeatTime + tolerance && guard < 8) {
                beatMisses++
                beatGridIndex++
                nextBeatTime += beatPeriodSec
                guard++
            }
        }
    }

    private fun recordBeat(subEnergy: Double, flux: Double, broadband: Double) {
        val delta = if (firstBeatRecorded) flux - lastBeatFlux else 0.0
        firstBeatRecorded = true
        lastBeatFlux = flux
        if (delta > maxFluxDelta) maxFluxDelta = delta
        if (delta < minFluxDelta) minFluxDelta = delta
        if (subEnergy > maxSub) maxSub = subEnergy
        if (broadband > maxBroad) maxBroad = broadband
        if (beatCount < MAX_BEATS) {
            beatSub[beatCount] = subEnergy
            beatFluxDelta[beatCount] = delta
            beatBroad[beatCount] = broadband
            beatCount++
        }
        val subNorm = subEnergy / (maxSub + EPS)
        val fluxNorm = (delta / (maxFluxDelta + EPS)).coerceAtLeast(0.0)
        phaseVotes[beatGridIndex % 4] += subNorm + fluxNorm
    }

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val output = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return output
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === EMPTY_BUFFER

    @Deprecated("Deprecated in AudioProcessor")
    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
        hopFill = 0
        synchronized(this) {
            if (beatPeriodSec > 0.0) {
                nextBeatTime = trackSeconds + beatPeriodSec
            }
        }
    }

    @Deprecated("Deprecated in AudioProcessor")
    override fun reset() {
        flush()
        sampleRate = 0
        channelCount = 0
        encoding = C.ENCODING_INVALID
    }

    private fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        return outputBuffer
    }

    companion object {
        private const val HOP_SIZE = 1024
        private const val MAX_BEATS = 512
        private const val MAX_ENERGY_SECONDS = 1200
        private const val MIN_SNAPSHOT_HOPS = 43
        private const val COMPLETE_CAP_MS = 30_000L
        private const val EPS = 1e-9
        private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}

data class BeatF(
    val sub01: Float,
    val flux01: Float,
    val broad01: Float,
)

data class TrackLearnSnapshot(
    val mediaId: String,
    val hopsObserved: Int,
    val durationObservedMs: Long,
    val bpmHint: Float?,
    val beatHits: Int,
    val beatMisses: Int,
    val beatPeriodMs: Float?,
    val downbeatBin: Int,
    val downbeatConfidence: Float,
    val beats: List<BeatF>,
    val energyPerSecond: FloatArray,
    val complete: Boolean,
)

private object Radix2Fft {

    fun transform(real: DoubleArray, imag: DoubleArray) {
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
                val tr = real[i]
                real[i] = real[j]
                real[j] = tr
                val ti = imag[i]
                imag[i] = imag[j]
                imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2.0 * PI / len
            val wReal = cos(angle)
            val wImag = sin(angle)
            var i = 0
            while (i < n) {
                var wr = 1.0
                var wi = 0.0
                val half = len / 2
                var k = 0
                while (k < half) {
                    val ar = real[i + k]
                    val ai = imag[i + k]
                    val br = real[i + k + half]
                    val bi = imag[i + k + half]
                    val vr = br * wr - bi * wi
                    val vi = br * wi + bi * wr
                    real[i + k] = ar + vr
                    imag[i + k] = ai + vi
                    real[i + k + half] = ar - vr
                    imag[i + k + half] = ai - vi
                    val nwr = wr * wReal - wi * wImag
                    wi = wr * wImag + wi * wReal
                    wr = nwr
                    k++
                }
                i += len
            }
            len = len shl 1
        }
    }
}
