package com.metrolist.innertube

import java.util.Locale

internal data class YouTubePlaybackTrackingParameters(
    val playback: Map<String, String>,
    val watchtime: Map<String, String>,
)

internal object YouTubePlaybackTrackingPolicy {
    private val lengthPattern = Regex("(?:[?&])len=([0-9]+(?:\\.[0-9]+)?)")

    // YouTube currently records this more consistently when playback and watchtime both report a
    // near-end position. The caller uses one CPN for both requests, matching yt-dlp's mark-watched flow.
    fun markWatchedParameters(
        playbackUrl: String,
        playedSeconds: Double,
    ): YouTubePlaybackTrackingParameters {
        val videoLength =
            lengthPattern
                .find(playbackUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()
                ?: playedSeconds.coerceAtLeast(1.5)
        val watchedUntil = (videoLength - 1.0).coerceAtLeast(0.5).trackingTime()

        val common =
            mapOf(
                "cmt" to watchedUntil,
                "el" to "detailpage",
            )
        return YouTubePlaybackTrackingParameters(
            playback = common,
            watchtime =
                common +
                    mapOf(
                        "st" to "0",
                        "et" to watchedUntil,
                    ),
        )
    }
}

internal fun Double.trackingTime(): String =
    String.format(Locale.US, "%.2f", this)
        .trimEnd('0')
        .trimEnd('.')
