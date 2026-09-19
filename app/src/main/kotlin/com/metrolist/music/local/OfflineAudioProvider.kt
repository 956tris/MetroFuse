/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Offline playback provider: matches a queued (usually streaming) track
 * against songs scanned from the phone's own storage (MediaStore) by
 * title/artist name so local files play instead of a network stream.
 */

package com.metrolist.music.local

import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.FormatEntity
import com.metrolist.music.db.entities.Song
import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.flow.first

object OfflineAudioProvider {
    data class Resolved(
        val localSongId: String,
        val title: String,
        val artist: String,
        val format: FormatEntity?,
        val mimeType: String?,
    )

    data class Query(
        val mediaId: String,
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
    )

    fun buildQuery(
        mediaId: String,
        song: Song?,
        queuedMetadata: MediaMetadata?,
    ): Query? {
        if (mediaId.startsWith("content://")) return null
        if (song?.song?.isLocal == true) return null
        if (song?.song?.isEpisode == true || queuedMetadata?.isEpisode == true) return null
        val title = song?.song?.title ?: queuedMetadata?.title
        if (title.isNullOrBlank()) return null
        val artists =
            song?.orderedArtists?.map { it.name }.orEmpty().filter { it.isNotBlank() }
                .ifEmpty { queuedMetadata?.artists?.map { it.name }.orEmpty().filter { it.isNotBlank() } }
        val album = song?.song?.albumName ?: song?.album?.title ?: queuedMetadata?.album?.title
        val durationMs =
            (song?.song?.duration ?: queuedMetadata?.duration)
                ?.takeIf { it > 0 }
                ?.toLong()
                ?.times(1000L)
        return Query(
            mediaId = mediaId,
            title = title,
            artists = artists,
            album = album,
            durationMs = durationMs,
        )
    }

    suspend fun findBestMatch(
        database: MusicDatabase,
        query: Query,
    ): Song? {
        val candidates =
            try {
                database.localSongsByNameAsc().first()
            } catch (_: Exception) {
                return null
            }
        if (candidates.isEmpty()) return null

        val queryTitle = normalize(query.title)
        if (queryTitle.isBlank()) return null
        val queryTitleTokens = tokens(queryTitle).toSet()
        val queryArtists = query.artists.map(::normalize).filter { it.isNotBlank() }
        val queryArtistTokens = queryArtists.flatMap(::tokens).toSet()
        val queryDurationMs = query.durationMs?.takeIf { it > 0 }

        var best: Song? = null
        var bestScore = MIN_MATCH_SCORE - 1
        for (candidate in candidates) {
            if (candidate.song.title.isBlank() || isUnknown(candidate.song.title)) continue
            val score =
                scoreCandidate(
                    candidate = candidate,
                    queryTitle = queryTitle,
                    queryTitleTokens = queryTitleTokens,
                    queryArtists = queryArtists,
                    queryArtistTokens = queryArtistTokens,
                    queryAlbum = query.album,
                    queryDurationMs = queryDurationMs,
                )
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best.takeIf { bestScore >= MIN_MATCH_SCORE }
    }

    suspend fun resolve(
        database: MusicDatabase,
        mediaId: String,
        song: Song?,
        queuedMetadata: MediaMetadata?,
    ): Resolved {
        val query = buildQuery(mediaId, song, queuedMetadata)
            ?: throw IllegalStateException("Offline: not enough metadata to match")
        val match = findBestMatch(database, query)
            ?: throw IllegalStateException("Offline: no matching local file")
        val format = match.format
        return Resolved(
            localSongId = match.song.id,
            title = match.song.title,
            artist = match.orderedArtists.firstOrNull()?.name.orEmpty(),
            format = format,
            mimeType = format?.mimeType?.takeIf { it.isNotBlank() },
        )
    }

    private fun scoreCandidate(
        candidate: Song,
        queryTitle: String,
        queryTitleTokens: Set<String>,
        queryArtists: List<String>,
        queryArtistTokens: Set<String>,
        queryAlbum: String?,
        queryDurationMs: Long?,
    ): Int {
        val candidateTitle = normalize(candidate.song.title)
        if (candidateTitle.isBlank()) return 0
        val candidateTitleTokens = tokens(candidateTitle).toSet()
        val titleScore =
            when {
                candidateTitle == queryTitle -> 100
                candidateTitle.contains(queryTitle) || queryTitle.contains(candidateTitle) -> 70
                else -> {
                    // Token-set F1 on content words: catches word-order
                    // differences ("Love Me Do" vs "Do Love Me") without the
                    // 0.8 recall gate that rejected them before, while still
                    // rejecting unrelated titles sharing one stopword-free
                    // token.
                    val f1 = tokenF1(candidateTitleTokens, queryTitleTokens)
                    when {
                        f1 >= 0.8f -> 55
                        f1 >= 0.6f -> 35
                        else -> return 0
                    }
                }
            }

        val candidateArtists =
            candidate.orderedArtists.map { normalize(it.name) }.filter { it.isNotBlank() && !isUnknown(it) }
        val candidateArtistTokens = candidateArtists.flatMap(::tokens).toSet()
        val artistScore =
            if (queryArtists.isEmpty() || candidateArtists.isEmpty()) {
                0
            } else if (queryArtists.any { q -> candidateArtists.any { c -> c == q } }) {
                30
            } else if (queryArtists.any { q -> candidateArtists.any { c -> c.contains(q) || q.contains(c) } }) {
                20
            } else {
                // Token overlap as a last resort before rejecting: handles
                // "John Lennon" vs "Lennon, John" style tag differences.
                val overlap = tokenF1(candidateArtistTokens, queryArtistTokens)
                if (overlap >= 0.5f) {
                    15
                } else {
                    return 0
                }
            }

        val candidateDurationMs = candidate.song.duration.takeIf { it > 0 }?.toLong()?.times(1000L)
        if (queryDurationMs != null && queryDurationMs > 0 && candidateDurationMs != null && candidateDurationMs > 0) {
            // A large duration gap means a different recording (live,
            // extended, ringtone-length rip) even when names match.
            if (kotlin.math.abs(queryDurationMs - candidateDurationMs) > MAX_DURATION_DELTA_MS) return 0
        }
        val durationBonus =
            if (queryDurationMs != null && queryDurationMs > 0 && candidateDurationMs != null && candidateDurationMs > 0) {
                val durationDelta = kotlin.math.abs(queryDurationMs - candidateDurationMs)
                when {
                    durationDelta <= 5_000L -> 15
                    durationDelta <= 10_000L -> 10
                    else -> 0
                }
            } else {
                0
            }

        var albumBonus = 0
        val normalizedQueryAlbum = queryAlbum?.let(::normalize)
        val normalizedCandidateAlbum = (candidate.song.albumName ?: candidate.album?.title)?.let(::normalize)
        if (!normalizedQueryAlbum.isNullOrBlank() && !normalizedCandidateAlbum.isNullOrBlank() &&
            !isUnknown(normalizedQueryAlbum) && !isUnknown(normalizedCandidateAlbum)
        ) {
            albumBonus = when {
                normalizedCandidateAlbum == normalizedQueryAlbum -> 8
                normalizedCandidateAlbum.contains(normalizedQueryAlbum) ||
                    normalizedQueryAlbum.contains(normalizedCandidateAlbum) -> 4
                else -> 0
            }
        }

        return titleScore + artistScore + durationBonus + albumBonus
    }

    private fun tokenF1(
        candidateTokens: Set<String>,
        queryTokens: Set<String>,
    ): Float {
        if (candidateTokens.isEmpty() || queryTokens.isEmpty()) return 0f
        val intersection = candidateTokens.intersect(queryTokens).size.toFloat()
        if (intersection == 0f) return 0f
        val precision = intersection / candidateTokens.size
        val recall = intersection / queryTokens.size
        return 2 * precision * recall / (precision + recall)
    }

    private fun tokens(value: String): List<String> =
        value.split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in STOPWORDS }

    private fun isUnknown(value: String): Boolean {
        val normalized = value.trim().lowercase()
        return normalized == "unknown title" || normalized == "unknown artist" ||
            normalized == "unknown album" || normalized == "unknown"
    }

    private fun normalize(value: String): String {
        // Strip " - Topic" channel suffixes and bracketed credits
        // ("(feat. X)", "[with Y]") that live in the title on one side and
        // in the artist field on the other. Recording markers (remix, live,
        // acoustic, version) are deliberately kept: they denote a different
        // recording and must not fuzzy-match the original.
        var text = value.lowercase()
        text = text.replace(TOPIC_SUFFIX, " ")
        text = text.replace(BRACKETED_CREDIT, " ")
        for (noise in NOISE_SUFFIXES) {
            text = text.replace(noise, " ")
        }
        return text.replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(separator = " ")
    }

    private const val MIN_MATCH_SCORE = 80
    private const val MAX_DURATION_DELTA_MS = 15_000L

    private val TOPIC_SUFFIX = Regex("""\s+-\s*topic\s*$""")
    private val BRACKETED_CREDIT =
        Regex("""[(\[].*?\b(feat\.?|ft\.?|featuring|with|prod\.?|by)\b[^)\]]*[)\]]""")

    private val NOISE_SUFFIXES = listOf(
        "official music video",
        "official lyric video",
        "official audio",
        "official video",
        "music video",
        "lyric video",
        "remastered",
        "remaster",
        "explicit",
    )

    private val STOPWORDS = setOf(
        "the", "a", "an", "of", "and", "ft", "feat", "featuring", "vs",
    )
}
