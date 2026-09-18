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
        val queryArtists = query.artists.map(::normalize).filter { it.isNotBlank() }
        val queryDurationMs = query.durationMs?.takeIf { it > 0 }

        var best: Song? = null
        var bestScore = 0
        for (candidate in candidates) {
            if (candidate.song.title.isBlank() || isUnknown(candidate.song.title)) continue
            val score = scoreCandidate(candidate, queryTitle, queryArtists, query.album, queryDurationMs)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
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
        queryArtists: List<String>,
        queryAlbum: String?,
        queryDurationMs: Long?,
    ): Int {
        val candidateTitle = normalize(candidate.song.title)
        val titleScore =
            when {
                candidateTitle == queryTitle -> 100
                candidateTitle.contains(queryTitle) || queryTitle.contains(candidateTitle) -> 70
                tokenCoverScore(candidateTitle, queryTitle) >= 0.8f -> 40
                else -> return 0
            }

        val candidateArtists =
            candidate.orderedArtists.map { normalize(it.name) }.filter { it.isNotBlank() && !isUnknown(it) }
        val artistScore =
            if (queryArtists.isEmpty() || candidateArtists.isEmpty()) {
                0
            } else if (queryArtists.any { q -> candidateArtists.any { c -> c == q } }) {
                30
            } else if (queryArtists.any { q -> candidateArtists.any { c -> c.contains(q) || q.contains(c) } }) {
                20
            } else {
                return 0
            }

        val candidateDurationMs = candidate.song.duration.takeIf { it > 0 }?.toLong()?.times(1000L)
        if (queryDurationMs != null && queryDurationMs > 0 && candidateDurationMs != null && candidateDurationMs > 0) {
            val diffMs = kotlin.math.abs(queryDurationMs - candidateDurationMs)
            if (diffMs > 20_000L) return 0
        }
        val durationBonus =
            if (queryDurationMs != null && queryDurationMs > 0 && candidateDurationMs != null && candidateDurationMs > 0 &&
                kotlin.math.abs(queryDurationMs - candidateDurationMs) <= 10_000L
            ) {
                10
            } else {
                0
            }

        var albumBonus = 0
        val queryAlbum = queryAlbum?.let(::normalize)
        val candidateAlbum = (candidate.song.albumName ?: candidate.album?.title)?.let(::normalize)
        if (!queryAlbum.isNullOrBlank() && !candidateAlbum.isNullOrBlank() &&
            (candidateAlbum == queryAlbum || candidateAlbum.contains(queryAlbum) || queryAlbum.contains(candidateAlbum))
        ) {
            albumBonus = 5
        }

        return titleScore + artistScore + durationBonus + albumBonus
    }

    private fun tokenCoverScore(candidateTitle: String, queryTitle: String): Float {
        val queryTokens = tokens(queryTitle)
        if (queryTokens.isEmpty()) return 0f
        val candidateTokens = tokens(candidateTitle).toSet()
        if (candidateTokens.isEmpty()) return 0f
        val covered = queryTokens.count { it in candidateTokens }
        return covered.toFloat() / queryTokens.size.toFloat()
    }

    private fun tokens(value: String): List<String> =
        value.split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in STOPWORDS }

    private fun isUnknown(value: String): Boolean {
        val normalized = value.trim().lowercase()
        return normalized == "unknown title" || normalized == "unknown artist" ||
            normalized == "unknown album" || normalized == "unknown"
    }

    private fun normalize(value: String): String {
        var text = value.lowercase()
        for (noise in NOISE_SUFFIXES) {
            text = text.replace(noise, " ")
        }
        return text.replace(Regex("[^a-z0-9 ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(separator = " ")
    }

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
