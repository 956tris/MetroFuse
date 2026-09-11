/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.providers

import com.metrolist.music.constants.AudioProviderOrderItem
import com.metrolist.music.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderFallbackMatcherTest {
    @Test
    fun `accepts canonical match for a versioned YouTube video`() {
        val selected = ProviderFallbackMatcher.selectSafeCandidates(
            metadata = metadata(
                title = "Fujii Kaze - Shinunoga E-Wa (Sped Up) (tiktok version)",
                artist = "Andrew",
                durationSeconds = 138,
            ),
            candidates = listOf(
                candidate(
                    provider = AudioProviderOrderItem.DEEZER,
                    trackId = "123",
                    title = "Shinunoga E-Wa",
                    artist = "Fujii Kaze",
                    durationMs = 185_000L,
                ),
            ),
            providerOrder = listOf(AudioProviderOrderItem.DEEZER),
        )

        assertEquals(listOf("123"), selected.map { it.providerTrackId })
    }

    @Test
    fun `rejects unrelated search result`() {
        val selected = ProviderFallbackMatcher.selectSafeCandidates(
            metadata = metadata("My Jealousy slowed", "VLX", 120),
            candidates = listOf(
                candidate(
                    provider = AudioProviderOrderItem.DEEZER,
                    trackId = "wrong",
                    title = "Jealous",
                    artist = "Another Artist",
                    durationMs = 121_000L,
                ),
            ),
            providerOrder = listOf(AudioProviderOrderItem.DEEZER),
        )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun `rejects an exact title from the wrong artist`() {
        val selected = ProviderFallbackMatcher.selectSafeCandidates(
            metadata = metadata("All I Want Is You", "Rebzyyx, hoshie star", 151),
            candidates = listOf(
                candidate(
                    provider = AudioProviderOrderItem.AMAZON_MUSIC,
                    trackId = "wrong-artist",
                    title = "All I Want Is You",
                    artist = "Andy Grammer",
                    durationMs = 151_000L,
                ),
            ),
            providerOrder = listOf(AudioProviderOrderItem.AMAZON_MUSIC),
        )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun `ranks exact artist matches before provider preference`() {
        val selected = ProviderFallbackMatcher.selectSafeCandidates(
            metadata = metadata("All I Want Is You", "Rebzyyx, hoshie star", 151),
            candidates = listOf(
                candidate(
                    provider = AudioProviderOrderItem.AMAZON_MUSIC,
                    trackId = "title-only",
                    title = "All I Want Is You",
                    artist = "",
                    durationMs = 151_000L,
                ),
                candidate(
                    provider = AudioProviderOrderItem.YOUTUBE_MUSIC,
                    trackId = "real-video-id",
                    title = "All I Want Is You",
                    artist = "Rebzyyx, hoshie star",
                    durationMs = 151_000L,
                ),
            ),
            providerOrder = listOf(
                AudioProviderOrderItem.AMAZON_MUSIC,
                AudioProviderOrderItem.YOUTUBE_MUSIC,
            ),
        )

        assertEquals(
            listOf("real-video-id", "title-only"),
            selected.map { it.providerTrackId },
        )
    }

    @Test
    fun `rejects large duration mismatch for a regular track`() {
        val selected = ProviderFallbackMatcher.selectSafeCandidates(
            metadata = metadata("Shinunoga E-Wa", "Fujii Kaze", 180),
            candidates = listOf(
                candidate(
                    provider = AudioProviderOrderItem.DEEZER,
                    trackId = "too-long",
                    title = "Shinunoga E-Wa",
                    artist = "Fujii Kaze",
                    durationMs = 360_000L,
                ),
            ),
            providerOrder = listOf(AudioProviderOrderItem.DEEZER),
        )

        assertTrue(selected.isEmpty())
    }

    @Test
    fun `keeps provider order and one candidate per provider`() {
        val metadata = metadata("Shinunoga E-Wa", "Fujii Kaze", 185)
        val selected = ProviderFallbackMatcher.selectSafeCandidates(
            metadata = metadata,
            candidates = listOf(
                candidate(AudioProviderOrderItem.DEEZER, "deezer-weak", "Shinunoga E-Wa", "", 185_000L),
                candidate(AudioProviderOrderItem.TIDAL, "tidal", "Shinunoga E-Wa", "Fujii Kaze", 185_000L),
                candidate(AudioProviderOrderItem.DEEZER, "deezer-best", "Shinunoga E-Wa", "Fujii Kaze", 185_000L),
            ),
            providerOrder = listOf(AudioProviderOrderItem.DEEZER, AudioProviderOrderItem.TIDAL),
        )

        assertEquals(
            listOf("deezer-best", "tidal"),
            selected.map { it.providerTrackId },
        )
    }

    private fun metadata(
        title: String,
        artist: String,
        durationSeconds: Int,
    ) = MediaMetadata(
        id = "youtube-id",
        title = title,
        artists = listOf(MediaMetadata.Artist(id = null, name = artist)),
        duration = durationSeconds,
    )

    private fun candidate(
        provider: AudioProviderOrderItem,
        trackId: String,
        title: String,
        artist: String,
        durationMs: Long?,
    ) = ProviderMatchCandidate(
        provider = provider,
        providerTrackId = trackId,
        title = title,
        artist = artist,
        album = null,
        durationMs = durationMs,
    )
}
