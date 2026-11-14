package com.metrolist.shared.plugins

import dev.metrofuse.api.StreamDescriptor
import dev.metrofuse.api.StreamTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class PluginSabrTransportTest {
    private val locator = "https://fixture.googlevideo.com/sabr"

    @Test
    fun sabrMappingUsesHostClassesAndPreservesAudioAndSessionData() {
        val value = checkNotNull(pluginSabrBootstrap(descriptor(), "abcdefghijk"))
        assertEquals("abcdefghijk", value.videoId)
        assertEquals(locator, value.serverAbrStreamingUrl)
        assertContentEquals(byteArrayOf(1, 2, 3), value.videoPlaybackUstreamerConfig)
        assertContentEquals(byteArrayOf(4, 5, 6), value.poToken)
        assertEquals(251, value.audioFormat.itag)
        assertEquals(123L, value.audioFormat.lastModified)
        assertEquals(160, value.discardVideoFormat.itag)
        assertEquals(180000L, value.durationMs)
        assertEquals(123456L, value.contentLengthBytes)
        assertEquals("audio/webm", value.mimeType)
        assertEquals("fixture", value.tokenVisitorData)
    }

    @Test
    fun ordinaryStreamsDoNotEnterSabrAndMalformedSessionsAreRejected() {
        assertNull(pluginSabrBootstrap(descriptor().copy(transport = StreamTransport.RANGE_HTTP), "abcdefghijk"))
        assertFails { pluginSabrBootstrap(descriptor(), "other-video") }
        assertFails { pluginSabrBootstrap(descriptor().copy(locator = "http://fixture.googlevideo.com/sabr"), "abcdefghijk") }
        assertFails { pluginSabrBootstrap(descriptor().copy(attributes = emptyMap()), "abcdefghijk") }
        val root = Json.parseToJsonElement(checkNotNull(descriptor().attributes["metrofuse.sabr.v1"])) as JsonObject
        for ((key, value) in mapOf("videoPlaybackUstreamerConfig" to "!invalid!", "mimeType" to "video/mp4", "durationMs" to "-1")) {
            val malformed =
                descriptor().copy(
                    attributes =
                        mapOf("metrofuse.sabr.v1" to JsonObject(root + (key to JsonPrimitive(value))).toString()),
                )
            assertFails { pluginSabrBootstrap(malformed, "abcdefghijk") }
        }
    }

    private fun descriptor() =
        StreamDescriptor(
            transport = StreamTransport.SABR_UMP,
            locator = locator,
            attributes =
                mapOf(
                    "metrofuse.sabr.v1" to
                        buildJsonObject {
                            put("videoId", "abcdefghijk")
                            put("serverAbrStreamingUrl", locator)
                            put("videoPlaybackUstreamerConfig", "AQID")
                            put("clientName", 67)
                            put("clientVersion", "fixture")
                            put(
                                "audioFormat",
                                buildJsonObject {
                                    put("itag", 251)
                                    put("lastModified", 123L)
                                },
                            )
                            put(
                                "discardVideoFormat",
                                buildJsonObject {
                                    put("itag", 160)
                                    put("lastModified", 456L)
                                },
                            )
                            put("discardVideoHeight", 144)
                            put("durationMs", 180000L)
                            put("contentLengthBytes", 123456L)
                            put("mimeType", "audio/webm")
                            put("poToken", "BAUG")
                            put("tokenVisitorData", "fixture")
                        }.toString(),
                ),
        )
}
