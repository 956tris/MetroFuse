/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.constants.EnableBiniLyricsKey
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get

object BiniLyricsProvider : LyricsProvider {
    override val name = "BiniLyrics"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableBiniLyricsKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = BiniLyrics.getLyrics(title, artist, duration, album)
}
