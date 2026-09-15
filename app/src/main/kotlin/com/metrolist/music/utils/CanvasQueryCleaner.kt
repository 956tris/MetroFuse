/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

/**
 * Shared YouTube-Music-frontend title/artist cleaning for canvas + artwork
 * matching.
 *
 * The YTM frontend is the only major source without ISRCs, and its titles
 * carry video-style suffixes ("Official Music Video", "(Visualizer)",
 * "[Official Audio]") while auto-generated artist channels end in
 * "- Topic". Every text-matcher (Apple canvas search, Tidal animated
 * artwork, ISRC discovery) must compare the *cleaned* forms or YTM tracks
 * systematically fail to match the same song on other providers.
 *
 * Cleaning is idempotent: already-clean catalog titles (Apple/Deezer/
 * Spotify/Tidal) pass through unchanged, so applying it on both sides of
 * a comparison is always safe.
 *
 * Deliberately NOT stripped: remix / live / cover / acoustic / version
 * markers that denote a genuinely different recording with a different
 * canvas. Stripping those would fix the miss rate by creating wrong-track
 * matches.
 */
object CanvasQueryCleaner {
    private val topicSuffix = Regex("""\s+-\s*topic\s*$""", RegexOption.IGNORE_CASE)

    // "(Official Music Video)", "[Official Audio]", "(Lyric Video)", ...
    private val junkParen =
        Regex(
            """\s*[(\[].*?\b(official|music\s*videos?|lyric\s*videos?|visualiz(er|ar)|audio|m\s*/\s*v|\bmv\b).*?[)\]]""",
            RegexOption.IGNORE_CASE,
        )

    // "... - Official Music Video", "... - Visualizer", "... - MV"
    private val junkSuffix =
        Regex(
            """\s+-\s*(official\s+)?(music\s*videos?|lyric\s*videos?|visualiz(er|ar)|audio|m\s*/\s*v|\bmv\b)\s*$""",
            RegexOption.IGNORE_CASE,
        )

    fun cleanArtist(artist: String): String =
        artist.replace(topicSuffix, "").replace(Regex("""\s+"""), " ").trim()

    fun cleanTitle(title: String): String {
        var result = title.replace(junkParen, " ").replace(junkSuffix, "")
        result = result.replace(Regex("""\s+"""), " ").trim()
        return result.ifBlank { title.trim() }
    }
}
