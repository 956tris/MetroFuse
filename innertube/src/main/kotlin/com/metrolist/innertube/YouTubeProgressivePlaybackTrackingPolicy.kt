package com.metrolist.innertube

internal object YouTubeProgressivePlaybackTrackingPolicy {
    fun playbackParameters(): Map<String, String> =
        mapOf(
            "cmt" to "0",
            "el" to "detailpage",
            "rt" to "0",
            "state" to "playing",
        )

    fun watchtimeParameters(
        fromSeconds: Double,
        toSeconds: Double,
        elapsedSeconds: Double,
        state: String,
    ): Map<String, String> {
        val end = toSeconds.coerceAtLeast(0.0)
        val start = fromSeconds.coerceIn(0.0, end)
        val parameters = mutableMapOf(
            "cmt" to end.trackingTime(),
            "el" to "detailpage",
            "et" to end.trackingTime(),
            "lact" to "1",
            "rt" to elapsedSeconds.coerceAtLeast(0.0).trackingTime(),
            "st" to start.trackingTime(),
            "state" to state,
        )
        if (state == "ended") parameters["final"] = "1"
        return parameters
    }
}
