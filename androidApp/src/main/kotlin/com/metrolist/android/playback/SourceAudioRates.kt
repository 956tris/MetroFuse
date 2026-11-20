package com.metrolist.android.playback

import androidx.media3.common.Format
import com.metrolist.shared.youtube.playback.PlaybackResource
import kotlin.math.roundToLong

internal fun Format.withSourceAudioRates(
    resource: PlaybackResource,
    audioFileSize: Long?,
    durationMs: Long,
): Format {
    val sourceSampleRate = sampleRate.takeIf { it > 0 } ?: resource.sampleRate?.takeIf { it > 0 } ?: sampleRate
    val sourceBitrate =
        averageBitrate.takeIf { it > 0 }
            ?: resource.bitrate?.takeIf { it > 0 }
            ?: averageAudioFileBitrate(audioFileSize, durationMs)
            ?: averageBitrate
    if (sourceSampleRate == sampleRate && sourceBitrate == averageBitrate) return this

    return buildUpon()
        .setSampleRate(sourceSampleRate)
        .setAverageBitrate(sourceBitrate)
        .build()
}

private fun averageAudioFileBitrate(
    sizeBytes: Long?,
    durationMs: Long,
): Int? {
    if (sizeBytes == null || sizeBytes <= 0L || durationMs <= 0L) return null
    // The file includes container overhead, so this is an estimate of the average audio bitrate.
    val bitsPerSecond = (sizeBytes * 8_000.0 / durationMs).roundToLong()
    return bitsPerSecond.takeIf { it in 1..Int.MAX_VALUE }?.toInt()
}
