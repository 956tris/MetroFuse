package com.metrolist.shared.local

import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.AlbumArtistMap
import com.metrolist.shared.db.entities.AlbumEntity
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.SongAlbumMap
import com.metrolist.shared.db.entities.SongArtistMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.withDatabaseTransaction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Instant

data class LocalTrack(
    val uri: String,
    val title: String,
    val durationSeconds: Int = 0,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val artworkUri: String? = null,
    val dateModifiedMillis: Long? = null,
)

/** Imports platform scans without replacing user edits, likes, history or playlist membership. */
class LocalLibraryImporter(
    private val database: MusicDatabase,
) {
    private val mutex = Mutex()

    suspend fun importTracks(tracks: List<LocalTrack>): Int =
        mutex.withLock {
            val unique = tracks.distinctBy { it.uri }
            require(unique.all { (it.uri.startsWith("file:") || it.uri.startsWith("content:")) && it.title.isNotBlank() })
            if (unique.isEmpty()) return@withLock 0
            database.withDatabaseTransaction {
                val dao = database.dao()
                val existing = dao.getSongsByIds(unique.map { it.uri }).associateBy { it.id }
                val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
                val artists = unique.mapNotNull { it.artist?.trim()?.takeIf(String::isNotBlank) }.distinct()
                val artistIds = artists.associateWith { localMetadataId("LA", it) }
                val storedArtists =
                    artistIds.values
                        .toList()
                        .chunked(900)
                        .flatMap { dao.artistEntitiesByIds(it) }
                        .associateBy { it.id }
                dao.upsertArtistEntities(
                    artists.map { name ->
                        val id = artistIds.getValue(name)
                        (storedArtists[id] ?: ArtistEntity(id, name, isLocal = true)).copy(name = name, isLocal = true)
                    },
                )
                val imported =
                    unique.map { track ->
                        val saved = existing[track.uri]?.song
                        val albumName = track.album?.trim()?.takeIf(String::isNotBlank)
                        val albumId = albumName?.let { localMetadataId("LAL", "${it.length}:$it${track.artist?.trim().orEmpty()}") }
                        (saved ?: SongEntity(track.uri, track.title)).copy(
                            title = track.title,
                            duration = track.durationSeconds.takeIf { it > 0 } ?: saved?.duration ?: -1,
                            thumbnailUrl = track.artworkUri ?: saved?.thumbnailUrl,
                            albumId = albumId,
                            albumName = albumName,
                            year = track.year ?: saved?.year,
                            dateModified =
                                track.dateModifiedMillis?.let { Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC) }
                                    ?: saved?.dateModified,
                            inLibrary = saved?.inLibrary ?: now,
                            isLocal = true,
                            isUnavailable = false,
                        )
                    }
                val albumGroups = imported.filter { it.albumId != null }.groupBy { it.albumId!! }
                val storedAlbums =
                    albumGroups.keys
                        .toList()
                        .chunked(900)
                        .flatMap { dao.albumEntitiesByIds(it) }
                        .associateBy { it.id }
                dao.upsertAlbumEntities(
                    albumGroups.map { (id, songs) ->
                        val first = songs.first()
                        (storedAlbums[id] ?: AlbumEntity(id, title = first.albumName!!, songCount = 0, duration = 0)).copy(
                            title = first.albumName!!,
                            year = first.year,
                            thumbnailUrl = songs.firstNotNullOfOrNull { it.thumbnailUrl } ?: storedAlbums[id]?.thumbnailUrl,
                            inLibrary = storedAlbums[id]?.inLibrary ?: now,
                            isLocal = true,
                        )
                    },
                )
                dao.upsertSongEntities(imported)
                unique.forEach { track ->
                    dao.clearLocalSongArtists(track.uri)
                    dao.clearLocalSongAlbums(track.uri)
                    track.artist?.trim()?.let { artistIds[it] }?.let { artistId ->
                        dao.upsert(SongArtistMap(track.uri, artistId, 0))
                    }
                }
                val tracksByUri = unique.associateBy { it.uri }
                albumGroups.forEach { (albumId, songs) ->
                    songs
                        .sortedWith(compareBy({ tracksByUri[it.id]?.trackNumber ?: Int.MAX_VALUE }, { it.title }, { it.id }))
                        .forEachIndexed { index, song ->
                            dao.upsert(SongAlbumMap(song.id, albumId, tracksByUri[song.id]?.trackNumber ?: index))
                        }
                    val albumArtists = songs.mapNotNull { tracksByUri[it.id]?.artist?.trim()?.let(artistIds::get) }.distinct()
                    dao.clearAlbumArtists(albumId)
                    dao.upsertAlbumArtists(albumArtists.mapIndexed { index, artistId -> AlbumArtistMap(albumId, artistId, index) })
                    val allSongs = dao.storedAlbumSongs(albumId).first()
                    dao.upsert(
                        dao.album(albumId).first()!!.album.copy(
                            songCount = allSongs.size,
                            duration =
                                allSongs
                                    .sumOf {
                                        it.song.duration
                                            .coerceAtLeast(
                                                0,
                                            ).toLong()
                                    }.coerceAtMost(Int.MAX_VALUE.toLong())
                                    .toInt(),
                        ),
                    )
                }
                imported.count { existing[it.id]?.song?.inLibrary == null }
            }
        }
}

private fun localMetadataId(
    prefix: String,
    value: String,
): String = "$prefix:scanned:${Base64.UrlSafe.encode(value.encodeToByteArray())}"
