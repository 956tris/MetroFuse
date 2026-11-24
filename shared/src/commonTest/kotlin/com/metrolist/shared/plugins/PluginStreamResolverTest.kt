package com.metrolist.shared.plugins

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumPage
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.LyricsWithProvider
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.playback.NoPlayableSourceException
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.youtube.playback.LocalAudioSource
import com.metrolist.shared.youtube.playback.PlaybackNetworkState
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import com.metrolist.shared.youtube.playback.PlaybackResource
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginStreamResolverTest {
    private var previousServices: PluginServices? = null
    private lateinit var previousNetwork: PlaybackNetworkState
    private val upstreamResource = resource("upstream", "file:///upstream.wav")
    private val pluginResource = resource("plugin", "file:///plugin.wav")
    private val song = SongItem("metrofuse:fixture:song", "Fixture", null, emptyList(), null)

    @BeforeTest fun setUp() {
        previousServices = PluginServicesStore.current
        previousNetwork = PlaybackNetworkStateStore.current
        Settings.replaceSharedForTest(Settings(MapSettings()))
        PluginServicesStore.frontends.value = listOf(MusicFrontend("fixture", "Fixture"))
        Settings.shared.musicFrontendOwner = "fixture"
    }

    @AfterTest fun tearDown() {
        PluginServicesStore.current = previousServices
        PlaybackNetworkStateStore.publish(previousNetwork)
        Settings.replaceSharedForTest(null)
        PluginServicesStore.frontends.value = emptyList()
    }

    @Test fun offlineFrontendBlocksProvidersEvenWhenTheNetworkIsConnected() =
        runTest {
            PlaybackNetworkStateStore.publish(PlaybackNetworkState("fixture", connected = true))
            var calls = 0
            PluginServicesStore.current =
                services {
                    calls++
                    resource("remote", "https://example.org/audio.mp3")
                }
            selectMusicFrontend("")
            val resolver = PluginStreamResolver(LocalAudioSource { id, _ -> upstreamResource.takeIf { id == "downloaded" } })
            assertEquals(upstreamResource, resolver.resolve("downloaded"))
            assertFailsWith<NoPlayableSourceException> { resolver.resolve("remote") }
            assertFailsWith<NoPlayableSourceException> { resolver.refresh("remote") }
            assertFailsWith<NoPlayableSourceException> { resolver.resolveRemote("remote", ContentHints(), AudioQuality.AUTO) }
            assertEquals(0, calls)
            assertEquals("", effectiveMusicFrontendOwner())
            val repositoryClient = HttpClient(MockEngine { error("Offline frontend must not contact YouTube") })
            try {
                val repository = YouTubeRepository(InnerTube(repositoryClient))
                assertTrue(repository.search("Fixture").isEmpty())
                assertTrue(repository.getArtist("metrofuse:fixture:artist").sections.isEmpty())
            } finally {
                repositoryClient.close()
            }
        }

    @Test fun switchingToOfflineRejectsAnInFlightOnlineResolution() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            PluginServicesStore.current =
                services {
                    entered.complete(Unit)
                    release.await()
                    resource("remote", "https://example.org/audio.mp3")
                }
            val result = async { runCatching { PluginStreamResolver().resolve("remote") } }
            entered.await()
            selectMusicFrontend("")
            release.complete(Unit)
            assertTrue(result.await().exceptionOrNull() is NoPlayableSourceException)
        }

    @Test fun absentOrUnavailableProviderReportsNoPlayableSource() =
        runTest {
            val resolver = PluginStreamResolver()
            PluginServicesStore.current = null
            assertFailsWith<NoPlayableSourceException> { resolver.resolve("song") }
            PluginServicesStore.current = services { null }
            assertFailsWith<NoPlayableSourceException> { resolver.resolve("song") }
        }

    @Test fun cachePrecedesProvidersAndRemoteResolutionKeepsHeaders() =
        runTest {
            var pluginCalls = 0
            PluginServicesStore.current =
                services {
                    pluginCalls++
                    pluginResource
                }
            val wrapper = PluginStreamResolver(LocalAudioSource { _, _ -> upstreamResource })
            assertEquals(upstreamResource, wrapper.resolve("song"))
            assertEquals(0, pluginCalls)
            val result = wrapper.resolveRemote("song", ContentHints(), AudioQuality.AUTO)
            assertEquals(pluginResource, result)
            assertEquals(mapOf("X-Fixture" to "fixture"), result.headers)
        }

    @Test fun localAndUnsupportedRequestsNeverReachRemoteAudioPlugins() =
        runTest {
            PluginServicesStore.current = services { error("Audio plugin must not intercept local or video requests") }
            val wrapper = PluginStreamResolver(LocalAudioSource { _, _ -> upstreamResource })
            val localHints = ContentHints(isLocal = true)
            assertEquals(upstreamResource, wrapper.resolve("song", localHints))
            assertEquals(upstreamResource, wrapper.refresh("song", localHints))
            assertFailsWith<NoPlayableSourceException> { wrapper.resolveRemote("song", localHints, AudioQuality.AUTO) }
            for (hints in listOf(ContentHints(wantVideo = true), ContentHints(playbackClientOverrideId = "fixture.client"))) {
                assertFailsWith<NoPlayableSourceException> { wrapper.resolve("song", hints) }
                assertFailsWith<NoPlayableSourceException> { wrapper.refresh("song", hints) }
                assertFailsWith<NoPlayableSourceException> { wrapper.resolveRemote("song", hints, AudioQuality.AUTO, refresh = true) }
            }
        }

    @Test fun videoRequestsDelegateToVideoHookWhenSupported() =
        runTest {
            PluginServicesStore.current =
                object : EmptyServices() {
                    override val supportsVideo: Boolean = true

                    override suspend fun resolve(
                        id: String,
                        song: SongItem?,
                        quality: AudioQuality,
                        refresh: Boolean,
                        excludedProviders: Set<String>,
                    ) = pluginResource

                    override suspend fun video(
                        id: String,
                        song: SongItem?,
                        audio: PlaybackResource,
                        maxHeight: Int?,
                        allowSabr: Boolean,
                        allowHls: Boolean,
                    ) = audio.copy(
                        videoUrl = "https://example.org/video.m3u8",
                        headers = audio.headers + ("Referer" to "https://example.org"),
                    )
                }
            val resolved = PluginStreamResolver().resolve("song", ContentHints(wantVideo = true))
            assertEquals("https://example.org/video.m3u8", resolved.videoUrl)
            assertEquals(mapOf("X-Fixture" to "fixture", "Referer" to "https://example.org"), resolved.headers)
        }

    @Test fun providerCancellationReachesCaller() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            var cancelled = false
            PluginServicesStore.current =
                services {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            val result = async { PluginStreamResolver().resolve("song") }
            entered.await()
            result.cancelAndJoin()
            assertTrue(cancelled)
            assertTrue(result.isCancelled)
        }

    @Test fun providerFilteredSearchAndCatalogPagesUsePluginBridgeWithoutUpstreamHttp() =
        runTest {
            PlaybackNetworkStateStore.publish(PlaybackNetworkState("fixture", connected = true))
            val album = AlbumItem("metrofuse:fixture:album", "Fixture album", null, emptyList())
            val artist = ArtistItem("metrofuse:fixture:artist", "Fixture artist", null)
            val playlist = PlaylistItem("metrofuse:fixture:playlist", "Fixture playlist", null)
            PluginServicesStore.current =
                object : EmptyServices() {
                    override suspend fun search(query: String) = listOf(song, album, artist, playlist)

                    override suspend fun album(id: String) = AlbumPage(album, listOf(song))

                    override suspend fun artist(id: String) = ArtistPageResult(mapOf("header" to listOf(artist), "songs" to listOf(song)))

                    override suspend fun playlist(id: String) = mapOf("header" to listOf(playlist), "songs" to listOf(song))
                }
            val client = HttpClient(MockEngine { error("Provider requests must not reach built-in YouTube") })
            try {
                val repository = YouTubeRepository(InnerTube(client))
                assertEquals(listOf(song), repository.searchFiltered("Fixture", SearchFilter.SONG, allowOfflineFallback = false).items)
                assertEquals(listOf(album), repository.searchFiltered("Fixture", SearchFilter.ALBUM).items)
                assertEquals(listOf(artist), repository.searchFiltered("Fixture", SearchFilter.ARTIST).items)
                assertEquals(listOf(playlist), repository.searchFiltered("Fixture", SearchFilter.COMMUNITY_PLAYLIST).items)
                assertEquals(listOf(song), repository.getAlbum(album.id)?.songs)
                assertEquals(listOf(song), repository.getArtist(artist.id).sections["songs"])
                assertEquals(listOf(song), repository.getPlaylist(playlist.id)["songs"])
            } finally {
                client.close()
            }
        }

    private fun services(resolve: suspend () -> PlaybackResource?) =
        object : EmptyServices() {
            override suspend fun resolve(
                id: String,
                song: SongItem?,
                quality: AudioQuality,
                refresh: Boolean,
                excludedProviders: Set<String>,
            ) = resolve()
        }

    private fun resource(
        id: String,
        uri: String,
    ) = PlaybackResource(id, uri, mapOf("X-Fixture" to "fixture"), null, null, requireBoundedRange = false)
}

private open class EmptyServices : PluginServices {
    override suspend fun resolve(
        id: String,
        song: SongItem?,
        quality: AudioQuality,
        refresh: Boolean,
        excludedProviders: Set<String>,
    ): PlaybackResource? = null

    override suspend fun lyrics(
        id: String,
        title: String,
        artist: String,
        durationMs: Long?,
    ): LyricsWithProvider? = null

    override suspend fun search(query: String): List<YTItem> = emptyList()

    override suspend fun metadata(id: String): SongItem? = null
}
