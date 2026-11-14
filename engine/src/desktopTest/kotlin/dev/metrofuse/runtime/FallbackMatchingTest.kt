package dev.metrofuse.runtime

import dev.metrofuse.api.MatchCandidate
import dev.metrofuse.api.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FallbackMatchingTest {
    @Test
    fun versionedUploadUsesExplicitTitleArtistCredit() {
        val source = Track("upload", "Fujii Kaze - Shinunoga E-Wa (Sped Up) (tiktok version)", "Andrew", "", 138_000)
        val result = ProviderFallbackMatcher.selectSafeCandidates(source, listOf(candidate("provider", "canonical", "Shinunoga E-Wa", "Fujii Kaze", 185_000)), listOf("provider"))
        assertEquals(listOf("canonical"), result.map { it.track.id })
    }

    @Test
    fun wrongArtistAndUnrelatedTitleAreRejected() {
        val source = Track("original", "All I Want Is You", "Rebzyyx, hoshie star", "", 151_000)
        val result = ProviderFallbackMatcher.selectSafeCandidates(source, listOf(candidate("a", "wrong-artist", source.title, "Andy Grammer", 151_000), candidate("b", "wrong-title", "Another song", source.artist, 151_000)), listOf("a", "b"))
        assertTrue(result.isEmpty())
        val official = source.copy(title = "All I Want Is You - Official Audio")
        val correct = ProviderFallbackMatcher.selectSafeCandidates(official, listOf(candidate("a", "correct", source.title, source.artist, 151_000)), listOf("a"))
        assertEquals(listOf("correct"), correct.map { it.track.id })
    }

    @Test
    fun durationMismatchRejectedForRegularTracksAndUnicodeNormalized() {
        val source = Track("original", "Café del Mar", "José", "", 180_000)
        val result = ProviderFallbackMatcher.selectSafeCandidates(source, listOf(candidate("a", "too-long", "Cafe del Mar", "Jose", 360_000), candidate("b", "correct", "Cafe del Mar", "Jose", 181_000)), listOf("a", "b"))
        assertEquals(listOf("correct"), result.map { it.track.id })
    }

    @Test
    fun confidenceRanksBeforePreferenceAndOnlyOneMatchPerProvider() {
        val source = Track("original", "Song", "Artist", "", 180_000)
        val result = ProviderFallbackMatcher.selectSafeCandidates(source, listOf(candidate("a", "weak", "Song", "", 180_000), candidate("b", "strong-b", "Song", "Artist", 180_000), candidate("a", "strong-a", "Song", "Artist", 180_000)), listOf("a", "b"), 4)
        assertEquals(listOf("strong-a", "strong-b"), result.map { it.track.id })
        assertTrue(ProviderFallbackMatcher.selectSafeCandidates(source, result, listOf("a", "b"), 0).isEmpty())
    }

    private fun candidate(provider: String, id: String, title: String, artist: String, duration: Long) = MatchCandidate(provider, Track(id, title, artist, "", duration))
}
