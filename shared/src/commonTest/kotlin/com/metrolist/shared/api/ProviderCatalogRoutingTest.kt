package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.plugins.PluginServices
import com.metrolist.shared.plugins.PluginServicesStore
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderCatalogRoutingTest {
    @Test
    fun providerHostSearchAndDetailsNeverCallYouTube() =
        runBlocking {
            val song = SongItem("metrofuse:fixture:track", "Track", null, emptyList(), null)
            val album = AlbumItem("metrofuse:fixture:album", "Album", null, emptyList())
            val artist = ArtistItem("metrofuse:fixture:artist", "Artist", null)
            val playlist = PlaylistItem("metrofuse:fixture:playlist", "Playlist", null)
            val items = listOf(song, album, artist, playlist)
            val client = HttpClient(MockEngine { error("Provider catalog must not issue YouTube requests") })
            val previous = PluginServicesStore.current
            val network = PlaybackNetworkStateStore.current
            Settings.replaceSharedForTest(Settings(MapSettings()))
            PlaybackNetworkStateStore.publish(PlaybackNetworkState(scope = "provider-catalog-fixture"))
            try {
                val repository = YouTubeRepository(InnerTube(client), usePluginCatalog = { true })
                PluginServicesStore.current = null
                assertTrue(repository.search("fixture").isEmpty())
                assertFalse(repository.searchFiltered("fixture", SearchFilter.SONG).onlineSearchFailed)

                PluginServicesStore.current =
                    object : PluginServices {
                        override suspend fun resolve(
                            id: String,
                            song: SongItem?,
                            quality: AudioQuality,
                            refresh: Boolean,
                            excludedProviders: Set<String>,
                        ) = null

                        override suspend fun lyrics(id: String, title: String, artist: String, durationMs: Long?) = null

                        override suspend fun search(query: String): List<YTItem> = items + song

                        override suspend fun metadata(id: String) = song.takeIf { it.id == id }

                        override suspend fun album(id: String) = AlbumPage(album, listOf(song)).takeIf { it.album.id == id }

                        override suspend fun artist(id: String) =
                            ArtistPageResult(mapOf("header" to listOf(artist), "songs" to listOf(song))).takeIf { artist.id == id }

                        override suspend fun playlist(id: String): Map<String, List<YTItem>>? =
                            mapOf("header" to listOf(playlist), "songs" to listOf(song)).takeIf { playlist.id == id }
                    }
                assertEquals(items, repository.search("fixture"))
                assertEquals(listOf(song), repository.searchFiltered("fixture", SearchFilter.SONG).items)
                assertEquals(listOf(album), repository.searchFiltered("fixture", SearchFilter.ALBUM).items)
                assertEquals(listOf(song), repository.getAlbum(album.id)?.songs)
                assertEquals(listOf(song), repository.getArtistSongs(artist.id))
                assertEquals(listOf(song), repository.getPlaylistPageData(playlist.id).songs)
                assertEquals(song, repository.getSongMetadata(song.id))
                assertNull(repository.getSongMetadata("legacy-id"))
                assertTrue(repository.startRadioQueue(song.id).songs.isEmpty())
                assertFalse(repository.isPlaybackReportingEnabled())
                PlaybackNetworkStateStore.publish(PlaybackNetworkState(scope = "provider-catalog-fixture", connected = false))
                assertTrue(repository.search("fixture").isEmpty())
            } finally {
                client.close()
                PluginServicesStore.current = previous
                PlaybackNetworkStateStore.publish(network)
                Settings.replaceSharedForTest(null)
            }
        }
}
