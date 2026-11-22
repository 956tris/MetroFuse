package dev.metrofuse.runtime

import dev.metrofuse.api.MatchCandidate
import dev.metrofuse.api.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FallbackMatchingTest {
    @Test
    fun bilingualTitlesMatchTheirOtherScriptWithoutAcceptingLongerRecordings() {
        val source = Track("original", "Shinunoga E-Wa", "Fujii Kaze", "", 185_000)
        val result =
            ProviderFallbackMatcher.selectSafeCandidates(
                source,
                listOf(
                    candidate("a", "longer", "死ぬのがいいわ - Shinunoga E-Wa", "Fujii Kaze", 255_000),
                    candidate("b", "correct", "死ぬのがいいわ - Shinunoga E-Wa", "Fujii Kaze", 186_000),
                    candidate("c", "different-title", "Another Song - Shinunoga E-Wa", "Fujii Kaze", 185_000),
                ),
                listOf("a", "b", "c"),
            )
        assertEquals(listOf("correct"), result.map { it.track.id })
    }

    @Test
    fun translatedParenthesesPreferTheRecordingWithAKnownMatchingDuration() {
        val source = Track("original", "Neon", "YUKIKA", "", 220_795)
        val result =
            ProviderFallbackMatcher.selectSafeCandidates(
                source,
                listOf(
                    candidate("a", "unknown-duration", "Neon", "YUKIKA", 0),
                    candidate("a", "translated", "NEON(네온)", "YUKIKA", 221_000),
                    candidate("b", "different-edition", "NEON 1989", "YUKIKA", 146_000),
                ),
                listOf("a", "b"),
            )
        assertEquals(listOf("translated"), result.map { it.track.id })
    }

    @Test
    fun aDashInsideRadioEditCreditsDoesNotReplaceTheActualArtist() {
        val source = Track("original", "Get Lucky (Radio Edit - feat. Pharrell Williams and Nile Rodgers)", "Daft Punk", "", 248_000)
        val result =
            ProviderFallbackMatcher.selectSafeCandidates(
                source,
                listOf(
                    candidate("a", "studio", "Get Lucky", "Daft Punk", 367_000),
                    candidate("b", "radio", source.title, "Daft Punk, Pharrell Williams, Nile Rodgers", 249_000),
                ),
                listOf("a", "b"),
            )
        assertEquals(listOf("radio"), result.map { it.track.id })
        assertTrue(ProviderFallbackMatcher.searchQueries(source).any { it == "get lucky Daft Punk" })
    }

    @Test
    fun versionedUploadUsesExplicitTitleArtistCredit() {
        val source = Track("upload", "Fujii Kaze - Shinunoga E-Wa (Sped Up) (tiktok version)", "Andrew", "", 138_000)
        val result =
            ProviderFallbackMatcher.selectSafeCandidates(
                source,
                listOf(candidate("provider", "canonical", "Shinunoga E-Wa", "Fujii Kaze", 185_000)),
                listOf("provider"),
            )
        assertEquals(listOf("canonical"), result.map { it.track.id })
    }

    @Test
    fun uploaderCreditDoesNotOutrankTheArtistNamedInTheUploadTitle() {
        val source = Track("upload", "Fujii Kaze - Shinunoga E-Wa (Sped Up)", "Andrew", "", 138_000)
        val result =
            ProviderFallbackMatcher.selectSafeCandidates(
                source,
                listOf(
                    candidate("a", "uploader-cover", "Shinunoga E-Wa", "Andrew", 138_000),
                    candidate("b", "recording", "Shinunoga E-Wa", "Fujii Kaze", 185_000),
                ),
                listOf("a", "b"),
            )
        assertEquals(listOf("recording"), result.map { it.track.id })
    }

    @Test
    fun wrongArtistAndUnrelatedTitleAreRejected() {
        val source = Track("original", "All I Want Is You", "Rebzyyx, hoshie star", "", 151_000)
        val result =
            ProviderFallbackMatcher.selectSafeCandidates(
                source,
                listOf(
                    candidate("a", "wrong-artist", source.title, "Andy Grammer", 151_000),
                    candidate("b", "wrong-title", "Another song", source.artist, 151_000),
                ),
                listOf("a", "b"),
            )
        assertTrue(result.isEmpty())
        val official = source.copy(title = "All I Want Is You - Official Audio")
        val correct =
            ProviderFallbackMatcher.selectSafeCandidates(
                official,
                listOf(candidate("a", "correct", source.title, source.artist, 151_000)),
                listOf("a"),
            )
        assertEquals(listOf("correct"), correct.map { it.track.id })
    }

    @Test
    fun durationMismatchRejectedForRegularTracksAndUnicodeNormalized() {
        val source = Track("original", "Café del Mar", "José", "", 180_000)
        val result =
            ProviderFallbackMatcher.selectSafeCandidates(
                source,
                listOf(
                    candidate("a", "too-long", "Cafe del Mar", "Jose", 360_000),
                    candidate("b", "correct", "Cafe del Mar", "Jose", 181_000),
                ),
                listOf("a", "b"),
            )
        assertEquals(listOf("correct"), result.map { it.track.id })
    }

    @Test
    fun confidenceRanksBeforePreferenceAndOnlyOneMatchPerProvider() {
        val source = Track("original", "Song", "Artist", "", 180_000)
        val result =
            ProviderFallbackMatcher.selectSafeCandidates(
                source,
                listOf(
                    candidate("a", "weak", "Song", "", 180_000),
                    candidate("b", "strong-b", "Song", "Artist", 180_000),
                    candidate("a", "strong-a", "Song", "Artist", 180_000),
                ),
                listOf("a", "b"),
                4,
            )
        assertEquals(listOf("strong-a", "strong-b"), result.map { it.track.id })
        assertTrue(ProviderFallbackMatcher.selectSafeCandidates(source, result, listOf("a", "b"), 0).isEmpty())
    }

    @Test
    fun shorterDifferentTitlesAndMissingArtistsAreNotAcceptedAsExactRecordings() {
        val source = Track("original", "Version of Me", "Artist", "", 180_000)
        val result =
            ProviderFallbackMatcher.selectSafeCandidates(
                source,
                listOf(
                    candidate("a", "shortened", "Of Me", "Artist", 180_000),
                    candidate("b", "unknown-artist", source.title, "", 180_000),
                    candidate("c", "correct", source.title, "Artist", 180_000),
                ),
                listOf("a", "b", "c"),
            )
        assertEquals(listOf("correct"), result.map { it.track.id })
    }

    @Test
    fun officialUploadDecorationsDoNotDisableDurationChecks() {
        val source = Track("original", "Artist - Song (Official Audio)", "Uploader", "", 180_000)
        val result =
            ProviderFallbackMatcher.selectSafeCandidates(
                source,
                listOf(
                    candidate("a", "extended", "Song", "Artist", 360_000),
                    candidate("b", "correct", "Song", "Artist", 181_000),
                ),
                listOf("a", "b"),
            )
        assertEquals(listOf("correct"), result.map { it.track.id })
    }

    @Test
    fun differentLiveRemixAndInstrumentalVersionsAreRejected() {
        val source = Track("original", "Song", "Artist", "", 180_000)
        for (version in listOf("Live", "Acoustic", "Remix", "Instrumental", "Cover", "Karaoke")) {
            assertTrue(
                ProviderFallbackMatcher
                    .selectSafeCandidates(
                        source,
                        listOf(candidate("a", "wrong-version", "Song ($version)", "Artist", 180_000)),
                        listOf("a"),
                    ).isEmpty(),
            )
        }
        val live = source.copy(title = "Song (Live)")
        val result =
            ProviderFallbackMatcher.selectSafeCandidates(
                live,
                listOf(
                    candidate("a", "studio", "Song", "Artist", 180_000),
                    candidate("b", "live", "Song - Live at Wembley", "Artist", 185_000),
                ),
                listOf("a", "b"),
            )
        assertEquals(listOf("live"), result.map { it.track.id })
    }

    @Test
    fun closestDurationWinsWithinTheSameProvider() {
        val source = Track("original", "Song", "Artist", "", 180_000)
        val result =
            ProviderFallbackMatcher.selectSafeCandidates(
                source,
                listOf(
                    candidate("a", "longer", "Song", "Artist", 210_000),
                    candidate("a", "closest", "Song", "Artist", 181_000),
                ),
                listOf("a"),
            )
        assertEquals(listOf("closest"), result.map { it.track.id })
    }

    @Test
    fun recordingCodesOutrankDifferentMetadataAndRejectConflictingCodes() {
        val source = Track("original", "Display title", "Old artist", "", 180_000, extras = mapOf("isrc" to "USUM72401234"))
        val exact = Track("exact", "Recording title", "Actual artist", "", 180_000, extras = mapOf("isrc" to "us-um7-24-01234"))
        val wrong = source.copy(id = "wrong-code", extras = mapOf("isrc" to "USUM72401235"))
        val result =
            ProviderFallbackMatcher.selectSafeCandidates(
                source,
                listOf(MatchCandidate("a", wrong), MatchCandidate("b", exact)),
                listOf("a", "b"),
            )
        assertEquals(listOf("exact"), result.map { it.track.id })
    }

    private fun candidate(
        provider: String,
        id: String,
        title: String,
        artist: String,
        duration: Long,
    ) = MatchCandidate(provider, Track(id, title, artist, "", duration))
}
