/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.providers

import com.metrolist.music.constants.AudioProviderOrderItem
import com.metrolist.music.constants.isPlaybackProvider
import org.json.JSONObject

data class ProviderMatchOverride(
    val provider: AudioProviderOrderItem,
    val providerTrackId: String,
    val label: String,
) {
    fun providerMediaId(): String =
        when (provider) {
            AudioProviderOrderItem.SOUNDCLOUD -> providerTrackId
            AudioProviderOrderItem.TIDAL -> "tidal:track:$providerTrackId"
            AudioProviderOrderItem.DEEZER -> "deezer:track:$providerTrackId"
            AudioProviderOrderItem.QOBUZ -> "qobuz:track:$providerTrackId"
            AudioProviderOrderItem.YOUTUBE_MUSIC -> providerTrackId
            AudioProviderOrderItem.INSTAGRAM -> providerTrackId
            AudioProviderOrderItem.AMAZON_MUSIC -> "amazon:track:$providerTrackId"
        }
}

data class ProviderMatchCandidate(
    val provider: AudioProviderOrderItem,
    val providerTrackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    val source: String = provider.displayName(),
    val shareUrl: String? = null,
) {
    val label: String
        get() = listOf(title, artist.takeIf { it.isNotBlank() })
            .filterNotNull()
            .joinToString(" - ")
}

object ProviderMatchOverrides {
    fun decode(value: String?): MutableMap<String, ProviderMatchOverride> {
        if (value.isNullOrBlank()) return mutableMapOf()
        return runCatching {
            val root = JSONObject(value)
            mutableMapOf<String, ProviderMatchOverride>().apply {
                root.keys().forEach { mediaId ->
                    val obj = root.optJSONObject(mediaId) ?: return@forEach
                    val provider = obj.optString("provider")
                        .takeIf { it.isNotBlank() }
                        ?.let { raw -> AudioProviderOrderItem.entries.find { it.name == raw } }
                        ?.takeIf { it.isPlaybackProvider() }
                        ?: return@forEach
                    val providerTrackId = obj.optString("trackId").takeIf { it.isNotBlank() } ?: return@forEach
                    put(
                        mediaId,
                        ProviderMatchOverride(
                            provider = provider,
                            providerTrackId = providerTrackId,
                            label = obj.optString("label").takeIf { it.isNotBlank() } ?: providerTrackId,
                        ),
                    )
                }
            }
        }.getOrDefault(mutableMapOf())
    }

    fun encode(overrides: Map<String, ProviderMatchOverride>): String {
        val root = JSONObject()
        overrides.forEach { (mediaId, override) ->
            if (!override.provider.isPlaybackProvider()) return@forEach
            root.put(
                mediaId,
                JSONObject()
                    .put("provider", override.provider.name)
                    .put("trackId", override.providerTrackId)
                    .put("label", override.label),
            )
        }
        return root.toString()
    }
}

fun AudioProviderOrderItem.displayName(): String =
    when (this) {
        AudioProviderOrderItem.SOUNDCLOUD -> "SoundCloud"
        AudioProviderOrderItem.TIDAL -> "TIDAL"
        AudioProviderOrderItem.DEEZER -> "Deezer"
        AudioProviderOrderItem.INSTAGRAM -> "Instagram"
        AudioProviderOrderItem.YOUTUBE_MUSIC -> "YouTube Music"
        AudioProviderOrderItem.QOBUZ -> "Qobuz"
        AudioProviderOrderItem.AMAZON_MUSIC -> "Amazon Music"
    }
