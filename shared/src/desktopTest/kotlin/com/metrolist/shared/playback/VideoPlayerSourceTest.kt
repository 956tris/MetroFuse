/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoPlayerSourceTest {
    @Test
    fun `HLS and DASH playlists use FFmpeg URL decoding`() {
        assertFalse(shouldUseRangeStreamForVideoUrl("https://example.com/cover.m3u8"))
        assertFalse(shouldUseRangeStreamForVideoUrl("https://example.com/COVER.M3U8?token=abc"))
        assertFalse(shouldUseRangeStreamForVideoUrl("https://example.com/manifest.mpd"))
    }

    @Test
    fun `any public HTTPS direct media uses the range stream`() {
        assertTrue(shouldUseRangeStreamForVideoUrl("https://media.example.com/videoplayback?token=abc"))
        assertTrue(shouldUseRangeStreamForVideoUrl("https://cdn.example.org/streams/media"))
        assertTrue(shouldUseRangeStreamForVideoUrl("https://cdn.example.net/image/video.mp4"))
        assertTrue(shouldUseRangeStreamForVideoUrl("https://cdn.example.com/media/stream.flac"))
    }

    @Test
    fun `insecure HTTP direct media uses FFmpeg URL decoding`() {
        assertFalse(shouldUseRangeStreamForVideoUrl("http://example.com/video.webm?token=abc"))
        assertFalse(shouldUseRangeStreamForVideoUrl("https://localhost/video.mp4"))
    }

    @Test
    fun `local media does not use the HTTP range stream`() {
        assertFalse(shouldUseRangeStreamForVideoUrl("/tmp/cover.mp4"))
        assertFalse(shouldUseRangeStreamForVideoUrl("file:///tmp/cover.mp4"))
    }

    @Test
    fun `looping timeline normalizes non-zero stream timestamps`() {
        val timeline = LoopingVideoTimeline()

        assertEquals(0L, timeline.normalize(10_042L))
        assertEquals(42L, timeline.normalize(10_084L))

        timeline.reset()

        assertEquals(0L, timeline.normalize(10_042L))
    }

    @Test
    fun `playback clock and frame delay respect playback speed`() {
        assertEquals(1_000L, scaledClockElapsedMs(500_000_000L, 2f))
        assertEquals(250_000_000L, frameDelayNanos(500L, 2f))
        assertEquals(1_000_000_000L, frameDelayNanos(500L, 0.5f))
        val positionAtSpeedChange = advancedClockPositionMs(0L, 500_000_000L, 1f)
        assertEquals(1_500L, advancedClockPositionMs(positionAtSpeedChange, 500_000_000L, 2f))
    }
}
