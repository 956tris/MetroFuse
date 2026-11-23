package com.metrolist.shared.playback.ffmpeg

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DirectMediaRedirectInterceptorTest {
    @Test
    fun allowsAnyPublicHttpsDomainAndPath() {
        for (url in listOf(
            "https://media.example.com/streams/track-123",
            "https://cdn.example.com:8443/tracks/song.flac",
            "https://stream.example.org/audio/playback",
        )) {
            val requests = mutableListOf<Request>()
            val client = client(requests, url, redirect = false)
            val initial =
                Request
                    .Builder()
                    .url(url)
                    .header("Range", "bytes=0-3")
                    .header("Authorization", "Bearer secret-key")
                    .header("Cookie", "session=abc")
                    .build()
            client.newCall(initial).execute().use { response ->
                assertEquals(206, response.code)
                assertEquals("media", response.body.string())
            }
            assertEquals(1, requests.size)
            assertEquals("Bearer secret-key", requests.single().header("Authorization"))
            assertEquals("session=abc", requests.single().header("Cookie"))
        }
    }

    @Test
    fun followsCrossDomainCdnRedirectsPreservingRangeAndStrippingAuthHeaders() {
        val requests = mutableListOf<Request>()
        val client = client(requests, "https://cdn2.example.net/audio/track.flac?redirected=1")
        val initial =
            Request
                .Builder()
                .url("https://origin.example.com/media/token")
                .header("Range", "bytes=10-14")
                .header("Authorization", "Bearer stream-key")
                .header("Cookie", "sid=123")
                .build()
        client.newCall(initial).execute().use { response ->
            assertEquals(206, response.code)
            assertEquals("media", response.body.string())
        }
        assertEquals(listOf("origin.example.com", "cdn2.example.net"), requests.map { it.url.host })
        assertEquals(listOf("bytes=10-14", "bytes=10-14"), requests.map { it.header("Range") })
        assertEquals("Bearer stream-key", requests[0].header("Authorization"))
        assertEquals("sid=123", requests[0].header("Cookie"))
        assertEquals(null, requests[1].header("Authorization"))
        assertEquals(null, requests[1].header("Cookie"))
    }

    @Test
    fun preservesAuthHeadersOnSameOriginRedirects() {
        val requests = mutableListOf<Request>()
        val client = client(requests, "https://origin.example.com/media/step2?redirected=1")
        val initial =
            Request
                .Builder()
                .url("https://origin.example.com/media/step1")
                .header("Range", "bytes=0-7")
                .header("Authorization", "Bearer stream-key")
                .header("Cookie", "sid=123")
                .build()
        client.newCall(initial).execute().use { response ->
            assertEquals(206, response.code)
        }
        assertEquals(listOf("Bearer stream-key", "Bearer stream-key"), requests.map { it.header("Authorization") })
        assertEquals(listOf("sid=123", "sid=123"), requests.map { it.header("Cookie") })
    }

    @Test
    fun rejectsInsecureOrLocalTargetsBeforeSendingHeaders() {
        for (target in listOf(
            "http://cdn.example.com/audio.flac",
            "https://user@cdn.example.com/audio.flac",
            "https://user:pass@cdn.example.com/audio.flac",
            "https://127.0.0.1/audio.flac",
            "https://localhost/audio.flac",
            "https://sub.localhost/audio.flac",
            "https://[::1]/audio.flac",
            "https://169.254.169.254/latest/meta-data",
        )) {
            val requests = mutableListOf<Request>()
            val client = client(requests, target)
            assertFailsWith<IOException> { client.newCall(request()).execute().close() }
            assertEquals(1, requests.size)
        }
    }

    @Test
    fun redirectLoopsAreBounded() {
        val requests = mutableListOf<Request>()
        val client = client(requests, "https://cdn.example.com/track.flac")
        assertFailsWith<IOException> { client.newCall(request()).execute().close() }
        assertEquals(6, requests.size)
    }

    private fun request() =
        Request
            .Builder()
            .url("https://cdn.example.com/track.flac")
            .header("Range", "bytes=10-14")
            .build()

    private fun client(
        requests: MutableList<Request>,
        target: String,
        redirect: Boolean = true,
    ) = OkHttpClient
        .Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(DirectMediaRedirectInterceptor)
        .addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val isFinal = !redirect || request.url.queryParameter("redirected") == "1"
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(if (isFinal) 206 else 302)
                .message(if (isFinal) "Partial Content" else "Redirect")
                .header("Location", target)
                .body((if (isFinal) "media" else "").toResponseBody())
                .build()
        }.build()
}
