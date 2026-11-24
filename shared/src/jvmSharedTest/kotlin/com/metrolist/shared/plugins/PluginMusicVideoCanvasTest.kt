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
    fun videoCanvasKeepsStreamMetadataForAnyPublicHttpsSource() {
        val response =
            Json
                .parseToJsonElement(
                    """{"url":"https://cdn.media-example.com/video/canvas.mp4?token=1","videoId":"musicVideo01","mimeType":"video/mp4","width":1280,"height":720,"durationSeconds":180,"headers":{"Referer":"https://example.com/","User-Agent":"Fixture"}}""",
                ).jsonObject
        val resource = assertNotNull(pluginMusicVideoCanvasResource("metrofuse:youtube:song", response))
        assertTrue(resource.hasVideo)
        assertEquals(response["url"].toString().trim('"'), resource.videoUrl)
        assertEquals("musicVideo01", resource.videoId)
        assertEquals("video/mp4", resource.videoMimeType)
        assertEquals(1280, resource.videoWidth)
        assertEquals(720, resource.videoHeight)
        assertEquals(180L, resource.durationSeconds)
        assertEquals(mapOf("Referer" to "https://example.com/", "User-Agent" to "Fixture"), resource.headers)
    }

    @Test
    fun sabrVideoCanvasParsesWithoutDirectUrl() {
        val sabrJson =
            """{"videoId":"musicVideo02","serverAbrStreamingUrl":"https://rr1.googlevideo.com/sabr","videoPlaybackUstreamerConfig":"AQID","clientName":67,"clientVersion":"1.20261112.01.00","audioFormat":{"itag":251,"lastModified":123},"discardVideoFormat":{"itag":160,"lastModified":456},"discardVideoHeight":144,"selectedVideoFormat":{"itag":244,"lastModified":789},"selectedVideoWidth":854,"selectedVideoHeight":480,"selectedVideoContentLengthBytes":4000000,"selectedVideoMimeType":"video/webm","selectedVideoBitrate":900000,"durationMs":195000,"contentLengthBytes":12345,"mimeType":"audio/webm"}"""
        val response =
            Json
                .parseToJsonElement(
                    """{"videoId":"musicVideo02","sabr":$sabrJson}""",
                ).jsonObject
        val resource = assertNotNull(pluginMusicVideoCanvasResource("metrofuse:metrofuse.youtube-music:atvSong", response))
        assertTrue(resource.hasVideo)
        assertTrue(resource.hasSabrVideo)
        assertEquals("musicVideo02", resource.videoId)
        assertEquals("sabr-video://musicVideo02", resource.videoUrl)
        assertEquals(854, resource.videoWidth)
        assertEquals(480, resource.videoHeight)
        assertEquals("video/webm", resource.videoMimeType)
        assertEquals(195L, resource.durationSeconds)
        assertEquals(4_000_000L, resource.videoContentLengthBytes)
    }

    @Test
    fun emptyDisabledResponsesAndUntrustedStreamsCannotBecomeCanvas() {
        assertNull(pluginMusicVideoCanvasResource("song", Json.parseToJsonElement("{}").jsonObject))
        for (url in listOf(
            "http://cdn.media-example.com/videoplayback",
            "https://user:secret@cdn.media-example.com/videoplayback",
            "https://cdn.media-example.com:8443/videoplayback",
            "https://localhost/video.mp4",
            "https://127.0.0.1/video.mp4",
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
