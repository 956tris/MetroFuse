package com.metrolist.shared.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioMatchOverridesTest {
    private val candidate =
        AudioMatchCandidate("provider.audio", "provider", "recording", "Song", "Artist", "Provider", durationMs = 180_000)

    @Test
    fun choicesRemainSeparateForEqualTrackIdsFromDifferentCatalogs() {
        val saved = mapOf("metrofuse:catalog-a:123" to candidate, "metrofuse:catalog-b:123" to candidate.copy(trackId = "other-recording"))
        assertEquals(saved, AudioMatchOverrides.decode(AudioMatchOverrides.encode(saved)))
        val restored = AudioMatchOverrides.decode(AudioMatchOverrides.encode(saved)).toMutableMap()
        restored.remove("metrofuse:catalog-a:123")
        assertEquals(candidate.copy(trackId = "other-recording"), restored["metrofuse:catalog-b:123"])
    }

    @Test
    fun invalidEntriesDoNotDiscardOtherSavedChoices() {
        val valid = Json.parseToJsonElement(AudioMatchOverrides.encode(mapOf("valid" to candidate))) as JsonObject
        val mixed = JsonObject(valid + ("invalid" to JsonPrimitive("broken record")))
        assertEquals(mapOf("valid" to candidate), AudioMatchOverrides.decode(mixed.toString()))
        assertTrue(AudioMatchOverrides.decode("malformed JSON").isEmpty())
    }

    @Test
    fun choicesContainMetadataWithoutStreamOrCredentialFields() {
        val record =
            (
                Json.parseToJsonElement(
                    AudioMatchOverrides.encode(mapOf("song" to candidate)),
                ) as JsonObject
            ).getValue("song") as JsonObject
        assertFalse(record.keys.any { it in setOf("stream", "locator", "headers", "authorization", "cookie") })
        assertFalse(AudioMatchOverrides.valid(candidate.copy(trackId = "")))
    }
}
