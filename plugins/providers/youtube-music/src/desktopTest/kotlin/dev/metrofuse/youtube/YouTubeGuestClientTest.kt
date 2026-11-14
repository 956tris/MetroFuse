package dev.metrofuse.youtube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubeGuestClientTest {
    @Test
    fun publicBootstrapSelectsTheMusicClientVersionFromNestedTuples() {
        val tuple = MutableList<JsonElement>(17) { JsonNull }
        tuple[15] = JsonPrimitive(67)
        tuple[16] = JsonPrimitive("1.20261112.01.00")
        val body = ")]}'\n" + JsonArray(listOf(JsonArray(listOf(JsonArray(tuple)))))
        val client = parseGuestMusicClient(body)
        assertEquals("WEB_REMIX", client?.clientName)
        assertEquals("1.20261112.01.00", client?.clientVersion)
        tuple[15] = JsonPrimitive(1)
        assertNull(parseGuestMusicClient(JsonArray(tuple).toString()))
        tuple[15] = JsonPrimitive(67)
        tuple[16] = JsonPrimitive("invalid")
        assertNull(parseGuestMusicClient(JsonArray(tuple).toString()))
        assertNull(parseGuestMusicClient("not a bootstrap response"))
    }
}
