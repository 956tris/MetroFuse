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
    private val versionDescriptor =
        Regex(
        """\b(sped\s*up|speed\s*up|slowed(?:\s*(?:and|&)\s*reverb)?|nightcore|tiktok(?:\s*version)?|official(?:\s+(?:music\s+)?(?:audio|video))?|lyrics?|visuali[sz]er|remaster(?:ed)?|edit|version)\b""",
            RegexOption.IGNORE_CASE,
        )
    private val bracketedText = Regex("""\(([^)]*)\)|\[([^]]*)]""")
    private val combiningMarks = Regex("\\p{M}+")
    private val nonAlphaNumeric = Regex("""[^\p{L}\p{N}]+""")
    private val featuredCredit = Regex("""\b(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val noiseSuffix =
        Regex(
            """(?:\s*[-–—|]\s*|\s+)(?:official(?:\s+music)?\s+(?:audio|video)|lyric(?:s)?(?:\s+video)?|visuali[sz]er|(?:\d{4}\s+)?remaster(?:ed)?(?:\s+\d{4})?)\s*$""",
            RegexOption.IGNORE_CASE,
        )
    private val recordingVersion =
        Regex(
            """\b(live|remix|acoustic|instrumental|karaoke|cover|demo|mashup|radio\s+edit)\b""",
            RegexOption.IGNORE_CASE,
        )
    private val speedVersion = Regex("""\b(sped\s*up|speed\s*up|slowed|nightcore)\b""", RegexOption.IGNORE_CASE)
    private val recordingSuffix =
        Regex(
            """\s+[-–—|]\s+.*\b(?:live|remix|acoustic|instrumental|karaoke|cover|demo|mashup|radio\s+edit|sped\s*up|speed\s*up|slowed|nightcore)\b.*$""",
            RegexOption.IGNORE_CASE,
        )

    internal fun searchQueries(metadata: Track): List<String> {
        val creditedArtist = titleArtistCredit(metadata)
        val artist = creditedArtist ?: metadata.extras["primaryArtist"]?.takeIf(String::isNotBlank) ?: metadata.artist
        val title = metadata.title.removePrefix(creditedArtist?.let { "$it - " }.orEmpty())
        val cleanTitle = normalizeTitle(title)
        val queries =
            listOfNotNull(
                normalizedIsrc(metadata)?.let { "isrc:$it" },
                listOf(metadata.title, metadata.artist).filter(String::isNotBlank).joinToString(" "),
                listOf(cleanTitle, artist).filter(String::isNotBlank).joinToString(" "),
                metadata.album.takeIf(String::isNotBlank)?.let { album ->
                    listOf(cleanTitle, artist, album).filter(String::isNotBlank).joinToString(" ")
                },
            )
        return queries.filter(String::isNotBlank).distinctBy { it.lowercase(Locale.ROOT) }.take(4)
    }

    override fun selectSafeCandidates(
        metadata: Track,
        candidates: List<MatchCandidate>,
        providerOrder: List<String>,
        maximumCandidates: Int,
    ): List<MatchCandidate> {
        val ranked = rankSafeCandidates(metadata, candidates, providerOrder)
        if (maximumCandidates <= 0) return emptyList()
        return ranked
            .distinctBy { it.provider }
            .take(maximumCandidates.coerceAtMost(512))
            .toList()
    }

    internal fun rankSafeCandidates(
        metadata: Track,
        candidates: List<MatchCandidate>,
        providerOrder: List<String>,
    ): Sequence<MatchCandidate> {
        require(candidates.size <= 4096 && providerOrder.size <= 512) { "Matching input exceeds limits" }
        val providerRank = providerOrder.withIndex().associate { it.value to it.index }
        return candidates
            .asSequence()
            .distinctBy { it.provider to it.track.id }
            .mapNotNull { candidate -> confidence(metadata, candidate.track)?.let { candidate to it } }
            .sortedWith(
                compareByDescending<Pair<MatchCandidate, Int>> { it.second }.thenBy {
                    providerRank[it.first.provider]
                        ?: Int.MAX_VALUE
                },
            ).map { it.first }
    }

    private fun confidence(
        metadata: Track,
        candidate: Track,
    ): Int? {
        val wantedIsrc = normalizedIsrc(metadata)
        val candidateIsrc = normalizedIsrc(candidate)
        if (wantedIsrc != null && candidateIsrc != null) {
            if (wantedIsrc != candidateIsrc) return null
            return 1000 + durationScore(metadata, candidate)
        }
        val titleCredit = titleArtistCredit(metadata)
        val wantedTitle = normalizeTitle(metadata.title.removePrefix(titleCredit?.let { "$it - " }.orEmpty()))
        val candidateTitle = normalizeTitle(candidate.title)
        if (wantedTitle.isBlank() || candidateTitle.isBlank()) return null
        val wantedTokens = wantedTitle.tokens()
        val candidateTokens = candidateTitle.tokens()
        if (candidateTokens.isEmpty()) return null
        val titleCoverage = candidateTokens.count(wantedTokens::contains).toDouble() / candidateTokens.size
        val wantedCoverage = wantedTokens.count(candidateTokens::contains).toDouble() / wantedTokens.size
        val titleContained = wantedTitle.contains(candidateTitle) || candidateTitle.contains(wantedTitle)
        val exactTitle = wantedTitle == candidateTitle
        val versionedSource = speedVersion.containsMatchIn(metadata.title)
        val wantedArtistTokens = normalizeArtist(metadata.artist).tokens()
        val candidateArtistTokens = normalizeArtist(candidate.artist).tokens()

        fun matchesArtist(wanted: Set<String>): Boolean {
            val coverage =
                if (candidateArtistTokens.isEmpty()) {
                    0.0
                } else {
                    candidateArtistTokens.count(wanted::contains).toDouble() /
                        candidateArtistTokens.size
                }
            val reverseCoverage = if (wanted.isEmpty()) 0.0 else wanted.count(candidateArtistTokens::contains).toDouble() / wanted.size
            return coverage >= MIN_TITLE_COVERAGE || reverseCoverage >= MIN_TITLE_COVERAGE
        }
        val titleArtistTokens = titleCredit?.let(::normalizeArtist)?.tokens().orEmpty()
        val artistMatches = if (titleArtistTokens.isNotEmpty()) matchesArtist(titleArtistTokens) else matchesArtist(wantedArtistTokens)
        if ((wantedArtistTokens.isNotEmpty() || titleArtistTokens.isNotEmpty()) && !artistMatches) return null
        if (!(exactTitle || (titleCoverage >= MIN_TITLE_COVERAGE && wantedCoverage >= MIN_TITLE_COVERAGE && artistMatches))) return null
        val wantedVersions = versions(metadata.title)
        val candidateVersions = versions(candidate.title)
        if (wantedVersions != candidateVersions) return null
        if (!versionedSource && speedVersion.containsMatchIn(candidate.title)) return null
        if (metadata.duration > 0 && candidate.duration > 0 && !versionedSource) {
            val difference = abs(metadata.duration - candidate.duration)
            val ratio = difference.toDouble() / metadata.duration.coerceAtLeast(1L)
            if (difference > MAX_DURATION_DIFFERENCE_MS || (difference > 12_000 && ratio > MAX_DURATION_DIFFERENCE_RATIO)) return null
        }
        val base =
            when {
                exactTitle && artistMatches -> 400
                exactTitle -> 350
                titleContained && artistMatches -> 300
                titleContained -> 250
                else -> 200
            }
        val albumScore = if (metadata.album.isNotBlank() && normalize(metadata.album) == normalize(candidate.album)) 10 else 0
        val speedScore = if (versionedSource && !speedVersion.containsMatchIn(candidate.title)) -80 else 0
        return base + durationScore(metadata, candidate) + albumScore + speedScore
    }

    private fun titleArtistCredit(track: Track): String? =
        track.title
            .takeIf { title ->
                title.contains(" - ") && (
                    speedVersion.containsMatchIn(title) || noiseSuffix.containsMatchIn(title) ||
                        bracketedText.findAll(title).any { versionDescriptor.containsMatchIn(it.value) }
                )
            }?.takeIf { title ->
                versionDescriptor.replace(title.substringAfter(" - "), " ").any(Char::isLetter)
            }?.substringBefore(" - ")
            ?.takeIf(String::isNotBlank)

    private fun normalizedIsrc(track: Track): String? =
        track.extras["isrc"]
            ?.let { MetadataMapping.normalizeIsrc(it) }
            ?.takeIf { it.matches(Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")) }

    private fun versions(title: String): Set<String> {
        val descriptors =
            bracketedText.findAll(title).map { it.value }.toList() +
                listOfNotNull(recordingSuffix.find(title)?.value)
        return descriptors.flatMap { text -> recordingVersion.findAll(text).map { normalize(it.value) }.toList() }.toSet()
    }

    private fun durationScore(
        metadata: Track,
        candidate: Track,
    ): Int =
        if (metadata.duration > 0 && candidate.duration > 0) {
            30 - (abs(metadata.duration - candidate.duration) / 1000).coerceAtMost(30).toInt()
        } else {
            0
        }

    private fun normalizeArtist(value: String): String =
        normalize(value)
            .replace(Regex("\\b(?:feat|ft|featuring|and)\\b"), " ")
            .trim()

    private fun normalizeTitle(value: String): String {
        val withoutDescriptors =
            bracketedText.replace(value) { match ->
                val contents =
                    match.groupValues
                        .drop(1)
                        .firstOrNull { it.isNotBlank() }
                        .orEmpty()
                if (versionDescriptor.containsMatchIn(contents) || recordingVersion.containsMatchIn(contents) ||
                    featuredCredit.containsMatchIn(contents)
                ) {
                    " "
                } else {
                    match.value
                }
            }
        return normalize(featuredCredit.replace(recordingSuffix.replace(noiseSuffix.replace(withoutDescriptors, " "), " "), " "))
    }

    private fun normalize(value: String): String =
        Normalizer
            .normalize(value, Normalizer.Form.NFD)
            .let { combiningMarks.replace(it, "") }
            .lowercase(Locale.ROOT)
            .let { nonAlphaNumeric.replace(it, " ") }
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun String.tokens(): Set<String> = split(' ').filterTo(linkedSetOf()) { it.isNotBlank() }
}
