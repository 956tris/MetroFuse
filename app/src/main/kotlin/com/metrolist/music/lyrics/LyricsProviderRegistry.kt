/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

object LyricsProviderRegistry {
    private val providerMap = mapOf(
        "BetterLyrics" to BetterLyricsProvider,
        "BiniLyrics" to BiniLyricsProvider,
        "PaxsenixAppleMusic" to PaxsenixAppleMusicLyricsProvider,
        "Musixmatch" to MusixmatchLyricsProvider,
        "PaxsenixQQMusic" to PaxsenixQQMusicLyricsProvider,
        "LrcLib" to LrcLibLyricsProvider,
        "KuGou" to KuGouLyricsProvider,
        "LyricsPlus" to LyricsPlusProvider,
        "Spotify" to SpotifyLyricsProvider,
        "Deezer" to DeezerLyricsProvider,
        "YouTubeSubtitle" to YouTubeSubtitleLyricsProvider,
        "YouTube" to YouTubeLyricsProvider,
    )

    val providerNames = providerMap.keys.toList()

    fun getProviderByName(name: String): LyricsProvider? = providerMap[name]

    fun getProviderName(provider: LyricsProvider): String? =
        providerMap.entries.find { it.value == provider }?.key

    fun deserializeProviderOrder(orderString: String): List<String> {
        if (orderString.isBlank()) {
            return getDefaultProviderOrder()
        }
        val saved = orderString.split(",").map { it.trim() }.filter { it in providerNames }
        // Merge in any providers that exist in the registry but aren't in the
        // saved order yet (e.g. newly added providers, or renames where the
        // old name got filtered out above). Preserves the user's existing
        // ordering and just appends anything missing, in registry order.
        val missing = providerNames.filter { it !in saved }
        return saved + missing
    }

    fun serializeProviderOrder(providers: List<String>): String {
        return providers.filter { it in providerNames }.joinToString(",")
    }

    fun getDefaultProviderOrder(): List<String> = listOf(
        "BetterLyrics",
        "BiniLyrics",
        "LrcLib",
        "Spotify",
        "Deezer",
        "KuGou",
        "PaxsenixAppleMusic",
        "Musixmatch",
        "PaxsenixQQMusic",
        "LyricsPlus",
        "YouTubeSubtitle",
        "YouTube",
    )

    fun getOrderedProviders(orderString: String): List<LyricsProvider> {
        val order = deserializeProviderOrder(orderString)
        return order.mapNotNull { getProviderByName(it) }
    }
}
