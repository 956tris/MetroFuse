/** Metrolist Project (C) 2026 Licensed under GPL-3.0 | See git history for contributors */

package com.metrolist.music.utils.mix

import kotlin.math.ceil
import kotlin.math.log10

/**
 * Learned structure of a single track. The Automix engine uses it to place the
 * mix-in seek on a downbeat past the intro and to start the blend so it lands
 * on the outro or fade tail.
 */
data class TrackStructure(
    val mediaId: String,
    val bpm: Float?,
    val beatPeriodMs: Float?,
    val downbeatOffsetMs: Long?,
    val introEndMs: Long?,
    val outroStartMs: Long?,
    val isFadeOut: Boolean,
    val confidence: Float,
    val learnedAt: Long,
) {
    companion object {
        /** Placeholder for a track with nothing learned yet; all musical fields null. */
        fun unknown(mediaId: String) =
            TrackStructure(
                mediaId = mediaId,
                bpm = null,
                beatPeriodMs = null,
                downbeatOffsetMs = null,
                introEndMs = null,
                outroStartMs = null,
                isFadeOut = false,
                confidence = 0f,
                learnedAt = 0L,
            )
    }
}

/** Energy-curve analysis of per-second buckets, before beat data is known. */
data class StructureResult(
    val introEndMs: Long?,
    val outroStartMs: Long?,
    val isFadeOut: Boolean,
    val confidence: Float,
)

/**
 * Derives intro/outro boundaries and fade-out likelihood from per-second energies.
 * Buckets are one second each, so bucket index maps directly to milliseconds.
 * Null boundaries mean "no quiet section found", not zero - callers fall back to
 * the track start/end.
 */
fun computeStructure(
    energyPerSecond: FloatArray,
    bucketCount: Int,
    durationMs: Long,
): StructureResult {
    val count = minOf(bucketCount, energyPerSecond.size)
    if (count <= 0) return StructureResult(null, null, false, 0f)
    var anyNonZero = false
    for (i in 0 until count) {
        if (energyPerSecond[i] != 0f) {
            anyNonZero = true
            break
        }
    }
    if (!anyNonZero) return StructureResult(null, null, false, 0f)

    val sorted = energyPerSecond.copyOf(count)
    sorted.sort()
    val median =
        if (count % 2 == 1) sorted[count / 2]
        else (sorted[count / 2 - 1] + sorted[count / 2]) / 2f

    var introEndMs: Long? = null
    val introThreshold = 0.6f * median
    for (i in 0 until count - 1) {
        if (energyPerSecond[i] > introThreshold && energyPerSecond[i + 1] > introThreshold) {
            introEndMs = i * 1000L
            break
        }
    }

    var outroStartMs: Long? = null
    val outroThreshold = 0.4f * median
    var lastHot = -1
    for (i in count - 1 downTo 0) {
        if (energyPerSecond[i] >= outroThreshold) {
            lastHot = i
            break
        }
    }
    if (lastHot >= 0 && lastHot < count - 1 && count - 1 - lastHot < 45) {
        outroStartMs = (lastHot + 1) * 1000L
    }

    var isFadeOut = false
    val tail = minOf(15, count)
    if (tail >= 2) {
        val eps = 1e-6
        val ys = DoubleArray(tail) { t -> log10(energyPerSecond[count - tail + t].toDouble() + eps) }
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumXY = 0.0
        for (t in 0 until tail) {
            sumX += t
            sumY += ys[t]
            sumXX += t.toDouble() * t
            sumXY += t * ys[t]
        }
        val n = tail.toDouble()
        val denom = n * sumXX - sumX * sumX
        if (denom != 0.0) {
            val slope = (n * sumXY - sumX * sumY) / denom
            val intercept = (sumY - slope * sumX) / n
            val meanY = sumY / n
            var ssTot = 0.0
            var ssRes = 0.0
            for (t in 0 until tail) {
                val fit = slope * t + intercept
                ssTot += (ys[t] - meanY) * (ys[t] - meanY)
                ssRes += (ys[t] - fit) * (ys[t] - fit)
            }
            val rSquared = if (ssTot > 0.0) 1.0 - ssRes / ssTot else 0.0
            isFadeOut = slope < -0.02 && rSquared > 0.6
        }
    }

    if (durationMs > 0) {
        introEndMs = introEndMs?.coerceAtMost(durationMs)
        outroStartMs = outroStartMs?.coerceAtMost(durationMs)
    }
    val found = (if (introEndMs != null) 1 else 0) + (if (outroStartMs != null) 1 else 0)
    val confidence =
        when {
            count < 10 -> 0f
            found == 2 -> 0.9f
            found == 1 -> 0.7f
            else -> 0.4f
        }.coerceIn(0f, 1f)
    return StructureResult(introEndMs, outroStartMs, isFadeOut, confidence)
}

/** Tempo guess from onset-flux autocorrelation, with normalized confidence. */
data class BpmEstimate(
    val bpm: Float,
    val confidence: Float,
)

/**
 * Estimates tempo by autocorrelating mean-removed flux at lags covering
 * [minBpm, maxBpm]. Returns null when the flux is flat or too short to cover
 * the slowest lag.
 */
fun estimateBpmFromFlux(
    fluxPerHop: FloatArray,
    hopRateHz: Float,
    minBpm: Float = 60f,
    maxBpm: Float = 200f,
): BpmEstimate? {
    val n = fluxPerHop.size
    if (n < 2 || hopRateHz <= 0f || minBpm <= 0f || maxBpm <= minBpm) return null
    var mean = 0.0
    for (v in fluxPerHop) mean += v
    mean /= n
    var totalEnergy = 0.0
    for (v in fluxPerHop) {
        val d = v - mean
        totalEnergy += d * d
    }
    if (totalEnergy <= 1e-9) return null

    val lagMin = maxOf((60f * hopRateHz / maxBpm).toInt(), 1)
    val lagMax = minOf(ceil(60.0 * hopRateHz / minBpm).toInt(), n - 1)
    if (lagMax <= lagMin) return null

    val rs = DoubleArray(lagMax - lagMin + 1)
    for (lag in lagMin..lagMax) {
        var r = 0.0
        var i = 0
        while (i + lag < n) {
            r += (fluxPerHop[i] - mean) * (fluxPerHop[i + lag] - mean)
            i++
        }
        rs[lag - lagMin] = r
    }
    var peak = lagMin
    for (lag in lagMin + 1..lagMax) {
        if (rs[lag - lagMin] > rs[peak - lagMin]) peak = lag
    }
    val peakVal = rs[peak - lagMin]
    if (peakVal <= 0.0) return null

    var second = 0.0
    for (lag in lagMin..lagMax) {
        if (lag < peak - 1 || lag > peak + 1) {
            second = maxOf(second, rs[lag - lagMin])
        }
    }
    val refined =
        if (peak > lagMin && peak < lagMax) {
            val y0 = rs[peak - 1 - lagMin]
            val y1 = peakVal
            val y2 = rs[peak + 1 - lagMin]
            val denom = y0 - 2 * y1 + y2
            val delta = if (denom != 0.0) (0.5 * (y0 - y2) / denom).coerceIn(-1.0, 1.0) else 0.0
            peak + delta
        } else {
            peak.toDouble()
        }
    val bpm = (60.0 * hopRateHz / refined).toFloat().coerceIn(minBpm, maxBpm)
    val share = peakVal / (totalEnergy + 1e-9)
    val margin = (peakVal - second) / (peakVal + 1e-9)
    val confidence = (share * margin).toFloat().coerceIn(0f, 1f)
    return BpmEstimate(bpm, confidence)
}

/** Per-beat normalized (0..1) features feeding the downbeat vote. */
data class BeatFeatures(
    val sub: Float,
    val fluxChange: Float,
    val broadband: Float,
)

/** Winning downbeat phase (beats to shift) with normalized confidence. */
data class DownbeatVote(
    val offsetBeats: Int,
    val confidence: Float,
)

/**
 * Votes the downbeat phase by folding sub-heavy beats into 4 phase bins.
 * Needs at least 16 beats (4 bars); shorter inputs still return the argmax
 * bin but with zero confidence.
 */
fun voteDownbeat(beats: List<BeatFeatures>): DownbeatVote {
    val bins = FloatArray(4)
    for (index in beats.indices) {
        val beat = beats[index]
        bins[index and 3] += beat.sub + 0.5f * beat.fluxChange
    }
    var top = 0
    for (b in 1..3) {
        if (bins[b] > bins[top]) top = b
    }
    if (beats.size < 16) return DownbeatVote(top, 0f)
    var runner = Float.NEGATIVE_INFINITY
    for (b in 0..3) {
        if (b != top && bins[b] > runner) runner = bins[b]
    }
    val margin = if (bins[top] > 0f) ((bins[top] - runner) / bins[top]).coerceIn(0f, 1f) else 0f
    val confidence = (margin * 0.6f + minOf(1f, beats.size / 64f) * 0.4f).coerceIn(0f, 1f)
    return DownbeatVote(top, confidence)
}

/**
 * Seek position for the incoming track: the first downbeat at or after the
 * intro end. Missing beat data falls back to [bpmFallback], missing everything
 * falls back to 0.
 */
fun mixInSeekMs(
    structure: TrackStructure,
    bpmFallback: Float?,
): Long {
    val period =
        structure.beatPeriodMs
            ?: bpmFallback?.takeIf { it > 0f }?.let { 60000f / it }
            ?: return 0L
    if (period <= 0f) return 0L
    val offset = structure.downbeatOffsetMs ?: 0L
    val introEnd = structure.introEndMs ?: 0L
    val steps = ceil((introEnd - offset).toDouble() / period)
    return (offset + steps * period).toLong().coerceAtLeast(0L)
}

/**
 * When to start the blend so it completes at the musical end of the outgoing
 * track. Fade-outs ride the tail 1.5s longer; the trigger never fires before
 * 15% of the track nor later than 750ms before the end.
 */
fun mixOutTriggerMs(
    structure: TrackStructure,
    trackDurationMs: Long,
    blendMs: Long,
): Long {
    var end = structure.outroStartMs ?: trackDurationMs
    if (structure.isFadeOut) end = minOf(trackDurationMs, end + 1500L)
    val low = (trackDurationMs * 0.15).toLong()
    val high = maxOf(end - 750L, low)
    return (end - blendMs).coerceIn(low, high)
}
