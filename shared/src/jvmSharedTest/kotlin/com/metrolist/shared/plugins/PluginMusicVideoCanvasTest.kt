package com.metrolist.shared.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginMusicVideoCanvasTest {
    @Test
    fun youtubeCanvasKeepsTheStreamMetadataNeededForMutedVideoPlayback() {
        val response =
            Json
                .parseToJsonElement(
                    """{"url":"https://rr1---sn-fixture.googlevideo.com/videoplayback?expire=2000000000","videoId":"musicVideo01","mimeType":"video/webm","width":1280,"height":720,"durationSeconds":180,"headers":{"Referer":"https://music.youtube.com/","User-Agent":"Fixture"}}""",
                ).jsonObject
        val resource = assertNotNull(pluginMusicVideoCanvasResource("metrofuse:youtube:song", response))
        assertTrue(resource.hasVideo)
        assertEquals(response["url"].toString().trim('"'), resource.videoUrl)
        assertEquals("musicVideo01", resource.videoId)
        assertEquals("video/webm", resource.videoMimeType)
        assertEquals(1280, resource.videoWidth)
        assertEquals(720, resource.videoHeight)
        assertEquals(180L, resource.durationSeconds)
        assertEquals(mapOf("Referer" to "https://music.youtube.com/", "User-Agent" to "Fixture"), resource.headers)
    }

    @Test
    fun emptyDisabledResponsesAndUntrustedStreamsCannotBecomeCanvas() {
        assertNull(pluginMusicVideoCanvasResource("song", Json.parseToJsonElement("{}").jsonObject))
        for (url in listOf(
            "http://rr1.googlevideo.com/videoplayback",
            "https://googlevideo.com.evil.example/videoplayback",
            "https://user:secret@rr1.googlevideo.com/videoplayback",
            "https://rr1.googlevideo.com:8443/videoplayback",
            "file:///tmp/canvas.mp4",
        )) {
            val response = Json.parseToJsonElement("""{"url":"$url"}""").jsonObject
            assertNull(pluginMusicVideoCanvasResource("song", response), url)
        }
    }

    @Test
    fun invalidHeadersAreDroppedWithoutLosingThePlayableCanvas() {
        val response =
            Json
                .parseToJsonElement(
                    """{"url":"https://rr1.googlevideo.com/videoplayback","headers":{"Referer":"https://music.youtube.com/","Bad Header":"value","X-Injected":"value\r\nOther: injected"}}""",
                ).jsonObject
        assertEquals(
            mapOf("Referer" to "https://music.youtube.com/"),
            assertNotNull(pluginMusicVideoCanvasResource("song", response)).headers,
        )
    }
}
