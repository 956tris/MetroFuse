package com.metrolist.innertube

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeProgressivePlaybackTrackingPolicyTest {
    @Test
    fun playbackStartsAtTheRealBeginning() {
        val parameters = YouTubeProgressivePlaybackTrackingPolicy.playbackParameters()

        assertEquals("0", parameters["cmt"])
        assertEquals("playing", parameters["state"])
    }

    @Test
    fun watchtimeUsesRealProgressInsteadOfForcingTheEnd() {
        val parameters =
            YouTubeProgressivePlaybackTrackingPolicy.watchtimeParameters(
                fromSeconds = 10.0,
                toSeconds = 20.25,
                elapsedSeconds = 20.5,
                state = "playing",
            )

        assertEquals("10", parameters["st"])
        assertEquals("20.25", parameters["et"])
        assertEquals("20.25", parameters["cmt"])
        assertEquals("playing", parameters["state"])
    }

    @Test
    fun backwardsSeekNeverCreatesAnInvalidRange() {
        val parameters =
            YouTubeProgressivePlaybackTrackingPolicy.watchtimeParameters(
                fromSeconds = 90.0,
                toSeconds = 30.0,
                elapsedSeconds = 45.0,
                state = "paused",
            )

        assertEquals("30", parameters["st"])
        assertEquals("30", parameters["et"])
    }

    @Test
    fun onlyAnActualEndIsMarkedFinal() {
        val paused =
            YouTubeProgressivePlaybackTrackingPolicy.watchtimeParameters(10.0, 20.0, 20.0, "paused")
        val ended =
            YouTubeProgressivePlaybackTrackingPolicy.watchtimeParameters(170.0, 180.0, 180.0, "ended")

        assertEquals(null, paused["final"])
        assertEquals("1", ended["final"])
    }
}
