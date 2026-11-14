package com.metrolist.shared.viewmodels

import androidx.compose.runtime.snapshots.Snapshot
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.Event
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.entities.SpeedDialItem
import com.metrolist.shared.db.getDesktopDatabaseBuilder
import com.metrolist.shared.local.LocalLibraryImporter
import com.metrolist.shared.local.LocalTrack
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.plugins.MusicFrontend
import com.metrolist.shared.plugins.PluginServicesStore
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineHomeTest {
    @Test
    fun offlineHomeIncludesOnlyPlayableSavedMusicAndReactsToPlaylistEdits() =
        runBlocking<Unit> {
            withHomeDatabase { database, scope, client ->
                val dao = database.dao()
                LocalLibraryImporter(database).importTracks(
                    listOf(LocalTrack("file:///Music/local.flac", "Local", artist = "Artist", album = "Local album")),
                )
                val local = requireNotNull(dao.getSongById("file:///Music/local.flac"))
                dao.update(local.song.copy(liked = true))
                val downloaded = SongEntity("download", "Downloaded", isDownloaded = true)
                val cached = SongEntity("cached", "Cached", isCached = true)
                val remote = SongEntity("remote", "Remote", liked = true)
                val unavailable = SongEntity("missing", "Missing", isLocal = true, isUnavailable = true)
                val video = SongEntity("video", "Video", isDownloaded = true, isVideo = true)
                listOf(downloaded, cached, remote, unavailable, video).forEach { dao.insert(it) }
                dao.insert(Event(songId = local.id, timestamp = LocalDateTime(2026, 11, 1, 10, 0), playTime = 1000))
                dao.insert(Event(songId = remote.id, timestamp = LocalDateTime(2026, 11, 1, 11, 0), playTime = 1000))
                dao.insert(PlaylistEntity("saved", "Saved playlist", isLocal = true))
                dao.insert(PlaylistSongMap(playlistId = "saved", songId = downloaded.id))
                database.speedDialDao().pin(SpeedDialItem.fromYTItem(song(local.id)))
                database.speedDialDao().pin(SpeedDialItem.fromYTItem(song(remote.id)))
                Settings.shared.lastHomePageJson = "legacy-upstream-cache"
                val model = HomeViewModel(database, scope, client, likedSongsName = "Liked", useUpstreamDiscovery = false)
                val state =
                    awaitHome(model) {
                        it.loadedHomeSongs().map { song -> song.id }.toSet() == setOf(local.id, downloaded.id, cached.id)
                    }

                assertEquals(listOf(local.id), state.recentlyPlayed.map { it.id })
                assertEquals(listOf(local.id), state.speedDialPinnedIds)
                assertEquals(listOf(local.id), state.speedDialItems.map { it.id })
                assertEquals(setOf("liked", "saved"), state.userPlaylists.map { it.id }.toSet())
                assertEquals(1, state.userPlaylists.single { it.id == "liked" }.songCount)
                assertTrue(state.sections.flatMap { it.items }.any { it.title == "Local album" })
                assertTrue(state.chips.isEmpty())
                assertTrue(state.discoverySections.isEmpty())
                assertFalse(state.showLoadingPlaceholder)
                assertEquals("legacy-upstream-cache", Settings.shared.lastHomePageJson)

                dao.insert(PlaylistEntity("changed", "Changed playlist", isLocal = true))
                dao.insert(PlaylistSongMap(playlistId = "changed", songId = cached.id))
                val changed = awaitHome(model) { it.userPlaylists.any { playlist -> playlist.id == "changed" } }
                assertEquals(1, changed.userPlaylists.single { it.id == "changed" }.songCount)
                dao.insert(PlaylistSongMap(playlistId = "changed", songId = downloaded.id, position = 1))
                awaitHome(model) { it.userPlaylists.single { playlist -> playlist.id == "changed" }.songCount == 2 }
            }
        }

    @Test
    fun changingOrDisablingTheFrontendRebuildsTheFeedAndRefreshCompletesAfterLoading() =
        runBlocking<Unit> {
            withHomeDatabase { database, scope, client ->
                val dao = database.dao()
                val offline = SongEntity("file:///Music/offline.wav", "Offline", isLocal = true)
                val first = SongEntity("metrofuse:first:one", "First provider")
                val second = SongEntity("metrofuse:second:two", "Second provider")
                listOf(offline, first, second).forEach { dao.insert(it) }
                PluginServicesStore.frontends.value = listOf(MusicFrontend("first", "First"), MusicFrontend("second", "Second"))
                val model = HomeViewModel(database, scope, client, likedSongsName = "Liked", useUpstreamDiscovery = false)
                awaitHome(model) { it.loadedHomeSongs().map { song -> song.id } == listOf(offline.id) }

                Settings.shared.musicFrontendOwner = "first"
                val firstFeed = awaitHome(model) { it.loadedHomeSongs().map { song -> song.id } == listOf(first.id) }
                assertFalse(firstFeed.loadedHomeSongs().any { it.isLocal })
                Settings.shared.musicFrontendOwner = "second"
                awaitHome(model) { it.loadedHomeSongs().map { song -> song.id } == listOf(second.id) }
                PluginServicesStore.frontends.value = emptyList()
                awaitHome(model) { it.loadedHomeSongs().map { song -> song.id } == listOf(offline.id) }

                var callbackSongs = emptyList<String>()
                val complete = Job()
                model.refresh {
                    callbackSongs =
                        model.uiState.value
                            .loadedHomeSongs()
                            .map { it.id }
                    complete.complete()
                }
                withTimeout(30_000) { complete.join() }
                assertEquals(listOf(offline.id), callbackSongs)
                assertEquals(1L, model.uiState.value.libraryRefreshGeneration)

                dao.update(offline.copy(isUnavailable = true))
                val empty = awaitHome(model) { it.loadedHomeSongs().isEmpty() && it.hasNoHomeContent() }
                assertFalse(empty.showLoadingPlaceholder)
            }
        }

    private suspend fun awaitHome(
        model: HomeViewModel,
        predicate: (HomeViewModel.HomeUiState) -> Boolean,
    ): HomeViewModel.HomeUiState {
        Snapshot.sendApplyNotifications()
        return withTimeout(30_000) { model.uiState.first { !it.isRestoringCache && predicate(it) } }
    }

    private suspend fun withHomeDatabase(block: suspend (MusicDatabase, CoroutineScope, HttpClient) -> Unit) {
        val previousFrontends = PluginServicesStore.frontends.value
        Settings.replaceSharedForTest(Settings(MapSettings()))
        PluginServicesStore.frontends.value = emptyList()
        val path = Files.createTempFile("offline-home", ".db")
        Files.delete(path)
        val database = getDesktopDatabaseBuilder(path.toFile()).build()
        val job = Job()
        var requests = 0
        val client =
            HttpClient(
                MockEngine {
                    requests++
                    error("Saved Home must not make network requests")
                },
            )
        try {
            block(database, CoroutineScope(currentCoroutineContext() + job), client)
            assertEquals(0, requests)
        } finally {
            job.cancelAndJoin()
            client.close()
            database.close()
            Files.deleteIfExists(path)
            PluginServicesStore.frontends.value = previousFrontends
            Settings.replaceSharedForTest(null)
        }
    }
}

private fun song(id: String) = SongItem(id, id, null, emptyList(), null)
