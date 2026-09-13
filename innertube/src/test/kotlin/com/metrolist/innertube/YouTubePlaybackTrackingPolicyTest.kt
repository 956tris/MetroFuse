package com.metrolist.innertube

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubePlaybackTrackingPolicyTest {
    @Test
    fun markWatchedUsesVideoLengthAndRequiredHistoryParameters() {
        val parameters =
            YouTubePlaybackTrackingPolicy.markWatchedParameters(
                playbackUrl = "https://s.youtube.com/api/stats/playback?docid=video&len=245.75&cmt=0",
                playedSeconds = 30.0,
            )

        assertEquals(
            mapOf(
                "cmt" to "244.75",
                "el" to "detailpage",
            ),
            parameters.playback,
        )
        assertEquals(
            mapOf(
                "cmt" to "244.75",
                "el" to "detailpage",
                "st" to "0",
                "et" to "244.75",
            ),
            parameters.watchtime,
        )
    }

    @Test
    fun markWatchedFallsBackToObservedPlaybackWhenLengthIsMissing() {
        val parameters =
            YouTubePlaybackTrackingPolicy.markWatchedParameters(
                playbackUrl = "https://s.youtube.com/api/stats/playback?docid=video",
                playedSeconds = 30.0,
            )

        assertEquals("29", parameters.playback["cmt"])
        assertEquals("29", parameters.watchtime["et"])
    }

    @Test
    fun markWatchedNeverSendsNegativeTime() {
        val parameters =
            YouTubePlaybackTrackingPolicy.markWatchedParameters(
                playbackUrl = "https://s.youtube.com/api/stats/playback?len=0.2",
                playedSeconds = 0.1,
            )

        assertEquals("0.5", parameters.playback["cmt"])
        assertEquals("0.5", parameters.watchtime["et"])
    }
}
