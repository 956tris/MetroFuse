package com.metrolist.shared.db

import com.metrolist.shared.db.entities.Song
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.BlockedContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

private data class OfflineRadioCandidate(
    val stored: Song,
    val item: SongItem,
    val sameAlbum: Boolean,
    val sharedArtists: Int,
)

internal suspend fun MusicDatabase.loadOfflineRadio(
    seedId: String? = null,
    availableSongIds: Set<String>? = null,
    random: Random = Random.Default,
): RadioQueuePage =
    withContext(Dispatchers.Default) {
        val dao = dao()
        val seed = seedId?.let { dao.getSongById(it) }
        val seedItem = seed?.toSongItem()
        val artistNames = seedItem?.artists.orEmpty().mapTo(mutableSetOf()) { it.name.trim().lowercase() }
        val seedAlbum = seedItem?.album
        val marked = dao.offlineQueueSongs()
        val markedIds = marked.mapTo(mutableSetOf()) { it.id }
        val indexed = availableSongIds?.let { dao.getSongsByIds((it - markedIds).toList()) }.orEmpty()
        val candidates =
            (marked + indexed)
                .filter { song ->
                    song.id != seedId && !song.song.isUnavailable &&
                        (song.song.isEpisode == (seed?.song?.isEpisode == true)) &&
                        if (availableSongIds != null) song.id in availableSongIds else song.isAvailableOffline(null)
                }.map { song ->
                    val item = song.toSongItem()
                    val album = item.album
                    OfflineRadioCandidate(
                        song,
                        item,
                        seedAlbum != null && album != null &&
                            (seedAlbum.id?.let { it == album.id } ?: seedAlbum.name.equals(album.name, ignoreCase = true)),
                        item.artists.count { it.name.trim().lowercase() in artistNames },
                    )
                }.shuffled(random)
                .sortedWith(
                    compareByDescending<OfflineRadioCandidate> { it.sameAlbum }
                        .thenByDescending { it.sharedArtists }
                        .thenByDescending { it.stored.song.liked }
                        .thenByDescending { it.stored.song.totalPlayTime },
                )
        RadioQueuePage(
            BlockedContent.filterSongs(candidates.map { it.item }),
            emptyList(),
            shuffleRecommendations = false,
        )
    }
