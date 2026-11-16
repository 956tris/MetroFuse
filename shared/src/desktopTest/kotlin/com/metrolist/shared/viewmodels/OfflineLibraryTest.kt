package com.metrolist.shared.viewmodels

import androidx.compose.runtime.snapshots.Snapshot
import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.db.getDesktopDatabaseBuilder
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalDateTime
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class OfflineLibraryTest {
    @Test
    fun offlineLibraryAndPaginatedContentSearchExcludeSavedRemoteTracks() =
        runBlocking<Unit> {
            val previousFrontends = PluginServicesStore.frontends.value
            Settings.replaceSharedForTest(Settings(MapSettings()))
            PluginServicesStore.frontends.value = emptyList()
            val path = Files.createTempFile("offline-library", ".db")
            Files.delete(path)
            val database = getDesktopDatabaseBuilder(path.toFile()).build()
            val job = Job()
            val client = HttpClient(MockEngine { error("Offline Library must not access the network") })
            try {
                val saved = LocalDateTime(2026, 11, 1, 10, 0)
                val tracks =
                    listOf(
                        SongEntity("remote", "Match A remote", inLibrary = saved),
                        SongEntity("file:///Music/local.wav", "Match B local", inLibrary = saved, isLocal = true),
                        SongEntity("download", "Match C download", inLibrary = saved, isDownloaded = true),
                        SongEntity("cached", "Match D cached", inLibrary = saved, isCached = true),
                    )
                tracks.forEach { database.dao().insert(it) }
                val providerSong = SongEntity("metrofuse:fixture:saved", "Match E selected provider", inLibrary = saved)
                database.dao().insert(providerSong)
                database.dao().insert(SongEntity("metrofuse:other:saved", "Match F other provider", inLibrary = saved))
                val expected = tracks.drop(1).map { it.id }.toSet()
                val model = LibraryViewModel(CoroutineScope(coroutineContext + job), database, useUpstreamLibrary = false)
                model.setActive(true)

                suspend fun awaitSongs(ids: Set<String>) {
                    Snapshot.sendApplyNotifications()
                    withTimeout(30_000) {
                        model.uiState.first { state ->
                            !state.isLoading && state.items
                                .filterIsInstance<SongItem>()
                                .map { it.id }
                                .toSet() == ids
                        }
                    }
                }
                awaitSongs(expected)
                val search =
                    SearchViewModel(
                        CoroutineScope(coroutineContext + job),
                        YouTubeRepository(InnerTube(client), databaseProvider = { database }, usePluginCatalog = { true }),
                    )
                val localRecent = SongItem(tracks[1].id, tracks[1].title, null, emptyList(), null, isLocal = true)
                val providerRecent = localRecent.copy(id = "metrofuse:fixture:recent", isLocal = false)
                search.addPlayedSearchSong(localRecent)
                search.addPlayedSearchSong(providerRecent)
                assertEquals(
                    listOf(localRecent.id),
                    search.uiState.value.recentSearchItems
                        .map { it.id },
                )
                val page = database.dao().contentMatchedSongs("Match", limit = 1, offset = 1, descending = false, offlineOnly = true)
                assertEquals(listOf("download"), page.map { it.id })
                assertEquals(expected, contentMatchedSongs(database, "Match", offlineOnly = true).map { it.id }.toSet())

                PluginServicesStore.frontends.value = listOf(MusicFrontend("fixture", "Fixture"))
                Settings.shared.musicFrontendOwner = "fixture"
                awaitSongs(setOf(providerSong.id))
                assertEquals(
                    setOf(providerSong.id),
                    contentMatchedSongs(database, "Match", providerOwner = "fixture").map { it.id }.toSet(),
                )
                withTimeout(30_000) { search.uiState.first { it.recentSearchItems.map { item -> item.id } == listOf(providerRecent.id) } }
                Settings.shared.musicFrontendOwner = ""
                awaitSongs(expected)
                withTimeout(30_000) { search.uiState.first { it.recentSearchItems.map { item -> item.id } == listOf(localRecent.id) } }
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
