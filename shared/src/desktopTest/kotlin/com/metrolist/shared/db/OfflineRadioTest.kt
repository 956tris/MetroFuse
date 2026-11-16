package com.metrolist.shared.db

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.entities.SongEntity
import com.metrolist.shared.model.RadioRecommendationHistory
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OfflineRadioTest {
    @Test
    fun offlineRadioUsesPlayableLocalDownloadedAndCachedAudioAndPreservesAffinity() =
        runBlocking {
            val path = Files.createTempFile("offline-radio", ".db")
            Files.delete(path)
            val db = getDesktopDatabaseBuilder(path.toFile()).build()
            val http = HttpClient(MockEngine { error("Offline queues cannot request YouTube") })
            val previousNetwork = PlaybackNetworkStateStore.current
            Settings.replaceSharedForTest(Settings(MapSettings()))
            try {
                val dao = db.dao()
                val seed =
                    SongEntity("seed", "Seed", customArtist = "Seed artist", albumId = "album", albumName = "Album", isDownloaded = true)
                dao.insert(seed)
                dao.insert(seed.copy(id = "local", title = "Local", isDownloaded = false, isLocal = true))
                dao.insert(seed.copy(id = "download", title = "Download", albumId = "different", albumName = "Different"))
                dao.insert(SongEntity("cache", "Cached", isCached = true, liked = true))
                dao.insert(SongEntity("indexed-cache", "Indexed cache"))
                dao.insert(seed.copy(id = "missing", title = "Missing"))
                dao.insert(seed.copy(id = "unavailable", title = "Unavailable", isUnavailable = true))
                dao.insert(seed.copy(id = "episode", title = "Episode", isEpisode = true))
                dao.insert(SongEntity("remote", "Remote", customArtist = "Seed artist", liked = true, totalPlayTime = 1_000_000))
                val available = setOf("seed", "local", "download", "cache", "indexed-cache", "unavailable", "episode")
                val page = db.loadOfflineRadio("seed", available, Random(1))
                assertEquals(listOf("local", "download", "cache", "indexed-cache"), page.songs.map { it.id })
                assertFalse(page.shuffleRecommendations)
                val selected =
                    RadioRecommendationHistory().select(
                        page,
                        { it },
                        { true },
                    ) { error("Offline radio has no remote continuation") }!!
                assertEquals(page.songs, selected.songs)

                val repository =
                    YouTubeRepository(
                        InnerTube(http),
                        databaseProvider = { db },
                        offlineSongAvailabilityProvider = { OfflineSongAvailability { available } },
                        usePluginCatalog = { true },
                    )
                PlaybackNetworkStateStore.publish(PlaybackNetworkState(connected = true, scope = "offline-source"))
                Settings.shared.musicFrontendOwner = "offline"
                assertEquals(listOf("local", "download", "cache", "indexed-cache"), repository.startRadioQueue("seed").songs.map { it.id })
                PlaybackNetworkStateStore.publish(PlaybackNetworkState(connected = false, scope = "offline-source"))
                assertEquals(
                    setOf("seed", "local", "download", "cache", "indexed-cache"),
                    repository
                        .continueRadioQueue("old-online-page")
                        .songs
                        .map { it.id }
                        .toSet(),
                )
                assertTrue(repository.startRadioQueue("seed").chips.isEmpty())
            } finally {
                Settings.replaceSharedForTest(null)
                PlaybackNetworkStateStore.publish(previousNetwork)
                http.close()
                db.close()
                Files.deleteIfExists(path)
            }
        }
}
