package com.metrolist.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCacheIndexTest {
    @Test
    fun providerCacheKeysMapBackToTheSongId() {
        assertEquals("song-id", PlaybackCacheIndex.mediaIdForKey("qobuz-fallback-v2:song-id"))
        assertEquals("song-id", PlaybackCacheIndex.mediaIdForKey("amazon-fallback-m4a:song-id"))
        assertEquals("song-id", PlaybackCacheIndex.mediaIdForKey("apple-music-fallback-audio:song-id"))
        assertEquals("song-id", PlaybackCacheIndex.mediaIdForKey("youtube-fallback-aac:song-id"))
    }

    @Test
    fun rawNetworkUrlsAreNotTreatedAsSongIds() {
        assertNull(PlaybackCacheIndex.mediaIdForKey("https://cdn.example/audio.m4a"))
    }

    @Test
    fun matchingKeysIncludeBaseAndFallbackResourcesOnly() {
        val keys =
            PlaybackCacheIndex.keysForMediaId(
                listOf(
                    "song-id",
                    "deezer-fallback-audio:song-id",
                    "youtube-fallback-aac:other-song",
                ),
                "song-id",
            )

        assertEquals(listOf("song-id", "deezer-fallback-audio:song-id"), keys)
    }

    @Test
    fun onlyACompletePositiveLengthResourceIsEligible() {
        assertTrue(PlaybackCacheIndex.isComplete(contentLength = 1_000L, cachedLength = 1_000L))
        assertTrue(PlaybackCacheIndex.isComplete(contentLength = 1_000L, cachedLength = 1_100L))
        assertFalse(PlaybackCacheIndex.isComplete(contentLength = 1_000L, cachedLength = 999L))
        assertFalse(PlaybackCacheIndex.isComplete(contentLength = 0L, cachedLength = 1_000L))
    }
}
