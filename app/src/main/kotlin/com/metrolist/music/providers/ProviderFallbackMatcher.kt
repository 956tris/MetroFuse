/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.providers

import com.metrolist.music.constants.AudioProviderOrderItem
import com.metrolist.music.models.MediaMetadata
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

object ProviderFallbackMatcher {
    private const val MIN_TITLE_COVERAGE = 0.8
    private const val MAX_DURATION_DIFFERENCE_MS = 45_000L
    private const val MAX_DURATION_DIFFERENCE_RATIO = 0.25

    private val versionDescriptor = Regex(
        """\b(sped\s*up|speed\s*up|slowed(?:\s*(?:and|&)\s*reverb)?|nightcore|tiktok(?:\s*version)?|official(?:\s*(?:audio|video))?|lyrics?|visuali[sz]er|remaster(?:ed)?|edit|version)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val bracketedText = Regex("""\(([^)]*)\)|\[([^]]*)]""")
    private val combiningMarks = Regex("\\p{M}+")
    private val nonAlphaNumeric = Regex("""[^\p{L}\p{N}]+""")

    fun selectSafeCandidates(
        metadata: MediaMetadata,
        candidates: List<ProviderMatchCandidate>,
        providerOrder: List<AudioProviderOrderItem>,
        maximumCandidates: Int = providerOrder.size.coerceAtLeast(1),
    ): List<ProviderMatchCandidate> {
        if (maximumCandidates <= 0) return emptyList()

        val providerRank = providerOrder.withIndex().associate { it.value to it.index }
        return candidates
            .asSequence()
            .distinctBy { it.provider to it.providerTrackId }
            .mapNotNull { candidate ->
                confidence(metadata, candidate)?.let { score -> candidate to score }
            }
            .sortedWith(
                compareByDescending<Pair<ProviderMatchCandidate, Int>> { it.second }
                    .thenBy { providerRank[it.first.provider] ?: Int.MAX_VALUE },
            )
            .distinctBy { it.first.provider }
            .take(maximumCandidates)
            .map { it.first }
            .toList()
    }

    private fun confidence(
        metadata: MediaMetadata,
        candidate: ProviderMatchCandidate,
    ): Int? {
        val wantedTitle = normalizeTitle(metadata.title)
        val candidateTitle = normalizeTitle(candidate.title)
        if (wantedTitle.isBlank() || candidateTitle.isBlank()) return null

        val wantedTokens = wantedTitle.tokens()
        val candidateTokens = candidateTitle.tokens()
        if (candidateTokens.isEmpty()) return null

        val titleCoverage = candidateTokens.count(wantedTokens::contains).toDouble() / candidateTokens.size
        val titleContained = wantedTitle.contains(candidateTitle) || candidateTitle.contains(wantedTitle)
        val exactTitle = wantedTitle == candidateTitle

        val wantedArtistTokens = normalize(
            metadata.artists.joinToString(" ") { it.name },
        ).tokens()
        val candidateArtistTokens = normalize(candidate.artist).tokens()
        val artistCoverage = if (candidateArtistTokens.isEmpty()) {
            0.0
        } else {
            candidateArtistTokens.count(wantedArtistTokens::contains).toDouble() / candidateArtistTokens.size
        }
        val reverseArtistCoverage = if (wantedArtistTokens.isEmpty()) {
            0.0
        } else {
            wantedArtistTokens.count(candidateArtistTokens::contains).toDouble() / wantedArtistTokens.size
        }
        val artistMatches =
            artistCoverage >= MIN_TITLE_COVERAGE ||
                reverseArtistCoverage >= MIN_TITLE_COVERAGE
        val hasComparableArtists = wantedArtistTokens.isNotEmpty() && candidateArtistTokens.isNotEmpty()
        if (hasComparableArtists && !artistMatches) return null

        val baseTitleMatches = exactTitle ||
            (titleCoverage >= MIN_TITLE_COVERAGE && (titleContained || artistMatches))
        if (!baseTitleMatches) return null

        val wantedDurationMs = metadata.duration.takeIf { it > 0 }?.toLong()?.times(1000L)
        val candidateDurationMs = candidate.durationMs?.takeIf { it > 0 }
        val versionedSource = versionDescriptor.containsMatchIn(metadata.title)
        if (wantedDurationMs != null && candidateDurationMs != null && !versionedSource) {
            val difference = abs(wantedDurationMs - candidateDurationMs)
            val ratio = difference.toDouble() / wantedDurationMs.coerceAtLeast(1L)
            if (difference > MAX_DURATION_DIFFERENCE_MS && ratio > MAX_DURATION_DIFFERENCE_RATIO) {
                return null
            }
        }

        return when {
            exactTitle && artistMatches -> 400
            exactTitle -> 350
            titleContained && artistMatches -> 300
            titleContained -> 250
            else -> 200
        }
    }

    private fun normalizeTitle(value: String): String {
        val withoutDescriptors = bracketedText.replace(value) { match ->
            val contents = match.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
            if (versionDescriptor.containsMatchIn(contents)) " " else match.value
        }
        return normalize(versionDescriptor.replace(withoutDescriptors, " "))
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .let { combiningMarks.replace(it, "") }
            .lowercase(Locale.ROOT)
            .let { nonAlphaNumeric.replace(it, " ") }
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun String.tokens(): Set<String> =
        split(' ').filterTo(linkedSetOf()) { it.isNotBlank() }
}
