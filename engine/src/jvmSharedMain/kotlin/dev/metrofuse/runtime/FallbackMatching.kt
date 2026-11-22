/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 * Adapted from MetroFuse ProviderFallbackMatcher.kt at
 * e2cca5bf2278f0345568a48adbfbfbd0046680f6 for the versioned MetroFuse host API.
 */
package dev.metrofuse.runtime

import dev.metrofuse.api.MatchCandidate
import dev.metrofuse.api.Track
import dev.metrofuse.api.TrackMatcher
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

object ProviderFallbackMatcher : TrackMatcher {
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

    override fun selectSafeCandidates(metadata: Track, candidates: List<MatchCandidate>, providerOrder: List<String>, maximumCandidates: Int): List<MatchCandidate> {
        val ranked = rankSafeCandidates(metadata, candidates, providerOrder)
        if (maximumCandidates <= 0) return emptyList()
        return ranked
            .distinctBy { it.provider }
            .take(maximumCandidates.coerceAtMost(512))
            .toList()
    }

    internal fun rankSafeCandidates(metadata: Track, candidates: List<MatchCandidate>, providerOrder: List<String>): Sequence<MatchCandidate> {
        require(candidates.size <= 4096 && providerOrder.size <= 512) { "Matching input exceeds limits" }
        val providerRank = providerOrder.withIndex().associate { it.value to it.index }
        return candidates.asSequence()
            .distinctBy { it.provider to it.track.id }
            .mapNotNull { candidate -> confidence(metadata, candidate.track)?.let { candidate to it } }
            .sortedWith(compareByDescending<Pair<MatchCandidate, Int>> { it.second }.thenBy { providerRank[it.first.provider] ?: Int.MAX_VALUE })
            .map { it.first }
    }

    private fun confidence(metadata: Track, candidate: Track): Int? {
        val wantedTitle = normalizeTitle(metadata.title)
        val candidateTitle = normalizeTitle(candidate.title)
        if (wantedTitle.isBlank() || candidateTitle.isBlank()) return null
        val wantedTokens = wantedTitle.tokens()
        val candidateTokens = candidateTitle.tokens()
        if (candidateTokens.isEmpty()) return null
        val titleCoverage = candidateTokens.count(wantedTokens::contains).toDouble() / candidateTokens.size
        val titleContained = wantedTitle.contains(candidateTitle) || candidateTitle.contains(wantedTitle)
        val exactTitle = wantedTitle == candidateTitle
        val versionedSource = versionDescriptor.containsMatchIn(metadata.title)
        // A versioned upload may credit its artist before " - " while its metadata names the uploader.
        val titleCredit = metadata.title.takeIf { versionedSource && it.contains(" - ") }?.substringBefore(" - ")
        val wantedArtistTokens = normalize(metadata.artist).tokens()
        val candidateArtistTokens = normalize(candidate.artist).tokens()
        fun matchesArtist(wanted: Set<String>): Boolean {
            val coverage = if (candidateArtistTokens.isEmpty()) 0.0 else candidateArtistTokens.count(wanted::contains).toDouble() / candidateArtistTokens.size
            val reverseCoverage = if (wanted.isEmpty()) 0.0 else wanted.count(candidateArtistTokens::contains).toDouble() / wanted.size
            return coverage >= MIN_TITLE_COVERAGE || reverseCoverage >= MIN_TITLE_COVERAGE
        }
        val titleArtistTokens = titleCredit?.let(::normalize)?.tokens().orEmpty()
        val artistMatches = matchesArtist(wantedArtistTokens) || matchesArtist(titleArtistTokens)
        if ((wantedArtistTokens.isNotEmpty() || titleArtistTokens.isNotEmpty()) && candidateArtistTokens.isNotEmpty() && !artistMatches) return null
        if (!(exactTitle || (titleCoverage >= MIN_TITLE_COVERAGE && (titleContained || artistMatches)))) return null
        if (metadata.duration > 0 && candidate.duration > 0 && !versionedSource) {
            val difference = abs(metadata.duration - candidate.duration)
            val ratio = difference.toDouble() / metadata.duration.coerceAtLeast(1L)
            if (difference > MAX_DURATION_DIFFERENCE_MS && ratio > MAX_DURATION_DIFFERENCE_RATIO) return null
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

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .let { combiningMarks.replace(it, "") }.lowercase(Locale.ROOT)
        .let { nonAlphaNumeric.replace(it, " ") }.trim().replace(Regex("\\s+"), " ")

    private fun String.tokens(): Set<String> = split(' ').filterTo(linkedSetOf()) { it.isNotBlank() }
}
