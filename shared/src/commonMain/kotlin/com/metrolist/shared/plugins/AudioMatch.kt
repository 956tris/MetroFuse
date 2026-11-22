/**
 * Metrolist Project (C) 2026, GPL-3.0. See Git history for contributors.
 * Adapted from MetroFuse ProviderMatchOverride at 5ba186773b5135d259da8d72f48c7b9492d7a08e.
 */
package com.metrolist.shared.plugins

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** Stores recording identity and display metadata, never resolved streams or credentials. */
@Serializable
data class AudioMatchCandidate(
    val provider: String,
    val owner: String,
    val trackId: String,
    val title: String,
    val artist: String,
    val providerName: String,
    val album: String = "",
    val durationMs: Long = 0,
    val artwork: String = "",
    val isrc: String? = null,
)

object AudioMatchOverrides {
    private val codec = Json { ignoreUnknownKeys = true }

    fun decode(value: String): Map<String, AudioMatchCandidate> {
        if (value.isBlank()) return emptyMap()
        val records = runCatching { codec.parseToJsonElement(value) as? JsonObject }.getOrNull() ?: return emptyMap()
        return records
            .mapNotNull { (id, record) ->
                if (id.isBlank()) return@mapNotNull null
                runCatching { codec.decodeFromJsonElement<AudioMatchCandidate>(record) }
                    .getOrNull()
                    ?.takeIf(::valid)
                    ?.let { id to it }
            }.toMap()
    }

    fun encode(value: Map<String, AudioMatchCandidate>): String = codec.encodeToString(value)

    fun valid(candidate: AudioMatchCandidate): Boolean =
        candidate.provider.isNotBlank() && candidate.owner.isNotBlank() && candidate.trackId.isNotBlank() &&
            candidate.trackId.length <= 2048 && candidate.title.isNotBlank() && candidate.title.length <= 8192 &&
            candidate.artist.length <= 8192 && candidate.album.length <= 8192 && candidate.artwork.length <= 8192
}

fun hasManualAudioMatch(id: String): Boolean =
    effectiveMusicFrontendOwner().isNotEmpty() && PluginServicesStore.current?.audioMatch(id) != null
