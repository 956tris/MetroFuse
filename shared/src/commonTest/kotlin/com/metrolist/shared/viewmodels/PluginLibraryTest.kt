package com.metrolist.shared.viewmodels

import androidx.compose.runtime.snapshots.Snapshot
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.plugins.MusicFrontend
import com.metrolist.shared.plugins.PluginLibraryAccount
import com.metrolist.shared.plugins.PluginLibraryPage
import com.metrolist.shared.plugins.PluginServices
import com.metrolist.shared.plugins.PluginServicesStore
import com.metrolist.shared.plugins.effectiveMusicFrontendOwner
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginLibraryTest {
    private class Services(
        var respond: suspend (String?) -> PluginLibraryPage?,
    ) : PluginServices {
        override suspend fun library(continuation: String?) = respond(continuation)

        override suspend fun search(query: String): List<YTItem> = emptyList()

        override suspend fun metadata(id: String): SongItem? = null

        override suspend fun resolve(
            id: String,
            song: SongItem?,
            quality: AudioQuality,
            refresh: Boolean,
            excludedProviders: Set<String>,
        ) = null

        override suspend fun lyrics(
            id: String,
            title: String,
            artist: String,
            durationMs: Long?,
        ) = null
    }

    private fun song(
        owner: String,
        id: String,
    ) = SongItem("metrofuse:$owner:$id", id, null, emptyList(), null)

    @Test
    fun libraryCollectsAllKindsAndKeepsPartialPagesOnFailureOrRepeatedTokens() =
        runBlocking {
            val previous = PluginServicesStore.current
            val frontends = PluginServicesStore.frontends.value
            val accounts = PluginServicesStore.libraryAccounts.value
            val client = HttpClient(MockEngine { error("Plugin library cannot use the old YouTube account") })
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { musicFrontendOwner = "plugin:fixture" })
            try {
                val track = song("fixture", "song")
                val album = AlbumItem("metrofuse:fixture:album", "Album", null, emptyList())
                val artist = ArtistItem("metrofuse:fixture:artist", "Artist", null)
                val playlist = PlaylistItem("metrofuse:fixture:playlist", "Playlist", null)
                val calls = mutableListOf<String?>()
                val services =
                    Services { token ->
                        calls += token
                        when (token) {
                            null -> PluginLibraryPage(listOf(track), "albums")
                            "albums" -> PluginLibraryPage(listOf(album), "empty")
                            "empty" -> PluginLibraryPage(continuation = "artists")
                            else -> PluginLibraryPage(listOf(artist, playlist, track))
                        }
                    }
                PluginServicesStore.current = services
                PluginServicesStore.frontends.value = listOf(MusicFrontend("fixture", "Fixture"))
                val repository = YouTubeRepository(InnerTube(client), usePluginCatalog = { true })
                val progress = mutableListOf<Int>()
                val result = repository.getCompleteLibraryItemsResult { progress += it.items.size }
                assertTrue(result.complete)
                assertEquals(listOf(track, album, artist, playlist), result.items)
                assertEquals(listOf(null, "albums", "empty", "artists"), calls)
                assertEquals(listOf(1, 2, 2, 4), progress)
                services.respond = { token -> if (token == null) PluginLibraryPage(listOf(track), "next") else error("offline") }
                val failed = repository.getCompleteLibraryItemsResult()
                assertFalse(failed.complete)
                assertEquals(listOf(track), failed.items)
                services.respond = { PluginLibraryPage(listOf(track), "same") }
                assertFalse(repository.getCompleteLibraryItemsResult().complete)
                services.respond = { error("Signed-out accounts cannot return a previous session's library") }
                PluginServicesStore.libraryAccounts.value = mapOf("fixture" to PluginLibraryAccount(1, loggedIn = false))
                val signedOut = repository.getCompleteLibraryItemsResult()
                assertTrue(signedOut.complete)
                assertTrue(signedOut.items.isEmpty())
            } finally {
                client.close()
                PluginServicesStore.current = previous
                PluginServicesStore.frontends.value = frontends
                PluginServicesStore.libraryAccounts.value = accounts
                Settings.replaceSharedForTest(null)
            }
        }

    @Test
    fun selectedPluginAndAccountChangesReplaceTheLibraryAndRejectOldResponses() =
        runBlocking<Unit> {
            val previous = PluginServicesStore.current
            val frontends = PluginServicesStore.frontends.value
            val accounts = PluginServicesStore.libraryAccounts.value
            val client = HttpClient(MockEngine { error("Plugin library cannot use the old YouTube account") })
            val job = Job()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var blockFirst = false
            Settings.replaceSharedForTest(Settings(MapSettings()).apply { musicFrontendOwner = "plugin:first" })
            try {
                PluginServicesStore.frontends.value = listOf(MusicFrontend("first", "First"), MusicFrontend("second", "Second"))
                PluginServicesStore.libraryAccounts.value =
                    mapOf(
                        "first" to PluginLibraryAccount(1, loggedIn = true),
                        "second" to PluginLibraryAccount(1, loggedIn = true),
                    )
                PluginServicesStore.current =
                    Services {
                        val owner = effectiveMusicFrontendOwner()
                        val account = PluginServicesStore.libraryAccounts.value.getValue(owner)
                        if (blockFirst && owner == "first" && account.revision == 1L) {
                            entered.complete(Unit)
                            withContext(NonCancellable) { release.await() }
                        }
                        PluginLibraryPage(if (account.loggedIn) listOf(song(owner, "account-${account.revision}")) else emptyList())
                    }
                val model =
                    LibraryViewModel(
                        CoroutineScope(coroutineContext + job),
                        repository = YouTubeRepository(InnerTube(client), usePluginCatalog = { true }),
                        useUpstreamLibrary = false,
                    )
                model.setActive(true)

                suspend fun awaitLibrary(expected: List<String>) {
                    Snapshot.sendApplyNotifications()
                    withTimeout(10_000) {
                        model.uiState.first { !it.isLoading && !it.isRefreshing && it.items.map(YTItem::id) == expected }
                    }
                }
                awaitLibrary(listOf("metrofuse:first:account-1"))
                blockFirst = true
                model.refresh()
                entered.await()
                PluginServicesStore.libraryAccounts.value = PluginServicesStore.libraryAccounts.value +
                    ("first" to PluginLibraryAccount(2, loggedIn = true))
                awaitLibrary(listOf("metrofuse:first:account-2"))
                release.complete(Unit)
                Settings.shared.musicFrontendOwner = "plugin:second"
                awaitLibrary(listOf("metrofuse:second:account-1"))
                PluginServicesStore.libraryAccounts.value = PluginServicesStore.libraryAccounts.value +
                    ("second" to PluginLibraryAccount(2, loggedIn = false))
                awaitLibrary(emptyList())
                Settings.shared.musicFrontendOwner = "offline"
                awaitLibrary(emptyList())
            } finally {
                release.complete(Unit)
                job.cancelAndJoin()
                client.close()
                PluginServicesStore.current = previous
                PluginServicesStore.frontends.value = frontends
                PluginServicesStore.libraryAccounts.value = accounts
                Settings.replaceSharedForTest(null)
            }
        }
}
