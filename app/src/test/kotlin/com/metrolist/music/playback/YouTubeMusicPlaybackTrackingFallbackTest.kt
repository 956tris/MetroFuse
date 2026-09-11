package com.metrolist.music.playback

import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeLocale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicPlaybackTrackingFallbackTest {
    @Test
    fun resolverStopsAtFirstClientWithTracking() =
        runBlocking {
            val attemptedClients = mutableListOf<String>()
            val clients =
                listOf(
                    YouTubeClient.WEB_REMIX,
                    YouTubeClient.ANDROID_MUSIC,
                    YouTubeClient.TVHTML5,
                )

            val result =
                resolveWithYouTubeClientFallback(clients) { client ->
                    attemptedClients += client.clientName
                    "tracking".takeIf { client == YouTubeClient.ANDROID_MUSIC }
                }

            assertEquals(YouTubeClient.ANDROID_MUSIC, result?.client)
            assertEquals("tracking", result?.value)
            assertEquals(listOf("WEB_REMIX", "ANDROID_MUSIC"), attemptedClients)
        }

    @Test
    fun resolverReturnsNullAfterEveryClientFails() =
        runBlocking {
            val clients = listOf(YouTubeClient.WEB_REMIX, YouTubeClient.ANDROID_MUSIC)
            val attemptedClients = mutableListOf<String>()

            val result =
                resolveWithYouTubeClientFallback<String>(clients) { client ->
                    attemptedClients += client.clientName
                    null
                }

            assertNull(result)
            assertEquals(listOf("WEB_REMIX", "ANDROID_MUSIC"), attemptedClients)
        }

    @Test
    fun progressiveTrackingFallbackOnlyUsesAuthenticatedClients() {
        assertTrue(YOUTUBE_MUSIC_HISTORY_TRACKING_CLIENTS.all(YouTubeClient::loginSupported))
    }

    @Test
    fun automaticHistoryTrackingUsesTheInternalFallbackOrder() {
        val clients = youTubeMusicHistoryTrackingClients()

        assertEquals(listOf("WEB_REMIX", "ANDROID_MUSIC", "WEB"), clients.map(YouTubeClient::clientName))
        assertEquals("7.27.52", clients[1].clientVersion)
        assertEquals("30", clients[1].androidSdkVersion)
        assertEquals("Android", clients[1].osName)
        assertEquals("11", clients[1].osVersion)
        assertTrue(!clients[1].sendDataSyncIdInContext)
    }

    @Test
    fun androidMusicTrackingKeepsDataSyncIdOutOfThePlayerContext() {
        val androidMusic = youTubeMusicHistoryTrackingClients()
            .single { it.clientName == "ANDROID_MUSIC" }

        val context =
            androidMusic.toContext(
                locale = YouTubeLocale(gl = "BR", hl = "pt-BR"),
                visitorData = "visitor",
                dataSyncId = "account-sync-id",
            )

        assertNull(context.user.onBehalfOfUser)
    }
}
