package com.metrolist.shared.local

import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.getDesktopDatabaseBuilder
import com.metrolist.shared.db.loadOfflineAlbum
import com.metrolist.shared.db.loadOfflineArtist
import com.metrolist.shared.db.searchOfflineMedia
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LocalLibraryImporterTest {
    @Test
    fun rescansPreserveUserStateAndReopenLocalCollections() =
        runTest {
            val path = Files.createTempFile("metrofuse-local-library", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).setQueryCoroutineContext(StandardTestDispatcher(testScheduler)).build()
            try {
                val importer = LocalLibraryImporter(database)
                val first = LocalTrack("file:///C:/Music/One%20Song.flac", "One Song", 180, "Local Artist", "Local Album", trackNumber = 2)
                val second =
                    LocalTrack("content://media/external/audio/media/42", "Other Song", 240, "Local Artist", "Local Album", trackNumber = 1)
                assertEquals(2, importer.importTracks(listOf(first, first, second)))
                val dao = database.dao()
                val stored = assertNotNull(dao.getSongById(first.uri))
                val userSong =
                    stored.song.copy(
                        liked = true,
                        likedDate = stored.song.inLibrary,
                        totalPlayTime = 12345,
                        customTitle = "My title",
                    )
                dao.upsert(userSong)
                dao.upsert(PlaylistEntity(id = "local-playlist", name = "My playlist", isLocal = true))
                dao.insert(PlaylistSongMap(songId = first.uri, playlistId = "local-playlist", position = 0))
                assertEquals(0, importer.importTracks(listOf(first.copy(title = "Updated tag", durationSeconds = 181))))
                val updated = assertNotNull(dao.getSongById(first.uri))
                assertEquals("Updated tag", updated.song.title)
                assertEquals("My title", updated.toSongItem().title)
                assertTrue(updated.song.liked)
                assertEquals(12345L, updated.song.totalPlayTime)
                assertEquals(stored.song.inLibrary, updated.song.inLibrary)
                assertEquals(listOf(first.uri), dao.playlistSongs("local-playlist").first().map { it.song.id })
                assertEquals(2, dao.allSongs().first().size)
                val album = assertNotNull(database.loadOfflineAlbum(assertNotNull(updated.song.albumId)))
                assertEquals(listOf(second.uri, first.uri), album.songs.map { it.id })
                assertEquals(421L, album.album.duration)
                val artistId =
                    assertNotNull(
                        updated
                            .toSongItem()
                            .artists
                            .single()
                            .id,
                    )
                assertTrue(
                    assertNotNull(
                        database.loadOfflineArtist(artistId),
                    ).sections.values.flatten().filterIsInstance<SongItem>().all { it.isLocal },
                )
                assertEquals(second.uri, assertNotNull(dao.getSongById(second.uri)).toSongItem().endpointParams)
                assertEquals(2, database.searchOfflineMedia("Local Artist").filterIsInstance<SongItem>().size)
                assertEquals(listOf(first.uri), dao.searchSongs("My title").first().map { it.id })
            } finally {
                database.close()
            }
            val reopened = getDesktopDatabaseBuilder(path.toFile()).setQueryCoroutineContext(StandardTestDispatcher(testScheduler)).build()
            try {
                val persisted = assertNotNull(reopened.dao().getSongById("file:///C:/Music/One%20Song.flac"))
                assertTrue(persisted.song.liked)
                assertEquals("My title", persisted.toSongItem().title)
                assertEquals(
                    2,
                    reopened
                        .dao()
                        .allSongs()
                        .first()
                        .size,
                )
            } finally {
                reopened.close()
                Files.deleteIfExists(path)
            }
        }

    @Test
    fun invalidScansAreAtomicAndAlbumIdentityIncludesArtist() =
        runTest {
            val path = Files.createTempFile("metrofuse-local-validation", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).setQueryCoroutineContext(StandardTestDispatcher(testScheduler)).build()
            try {
                val importer = LocalLibraryImporter(database)
                assertFailsWith<IllegalArgumentException> {
                    importer.importTracks(listOf(LocalTrack("file:///a.mp3", "A"), LocalTrack("https://example.org/a.mp3", "Remote")))
                }
                assertTrue(
                    database
                        .dao()
                        .allSongs()
                        .first()
                        .isEmpty(),
                )
                importer.importTracks(
                    listOf(
                        LocalTrack("file:///a.mp3", "A", artist = "Artist One", album = "Greatest Hits"),
                        LocalTrack("file:///b.mp3", "B", artist = "Artist Two", album = "Greatest Hits"),
                    ),
                )
                assertEquals(
                    2,
                    database
                        .dao()
                        .allSongs()
                        .first()
                        .map { it.song.albumId }
                        .toSet()
                        .size,
                )
            } finally {
                database.close()
                Files.deleteIfExists(path)
            }
        }
}
