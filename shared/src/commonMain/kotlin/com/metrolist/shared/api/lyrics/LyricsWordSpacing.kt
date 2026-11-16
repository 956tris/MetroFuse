/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics

/** Uses the full line's whitespace without inserting gaps between timed syllables. */
internal fun alignLyricsWordText(
    lineText: String,
    wordTexts: List<String>,
): List<String>? {
    val aligned = ArrayList<String>(wordTexts.size)
    var cursor = 0
    for (wordText in wordTexts) {
        val start = cursor
        val content = wordText.filterNot(Char::isWhitespace)
        if (content.isEmpty()) return null
        for (character in content) {
            while (cursor < lineText.length && lineText[cursor].isWhitespace()) cursor++
            if (cursor >= lineText.length || !lineText[cursor].equals(character, ignoreCase = true)) return null
            cursor++
        }
        while (cursor < lineText.length && lineText[cursor].isWhitespace()) cursor++
        aligned += lineText.substring(start, cursor)
    }
    return aligned.takeIf { cursor == lineText.length }
}

/**
 * MetroFuse sends whole words with no whitespace in them. When the line text has none either, the words would run
 * together ("Ohoh"), so every word except the last gets a trailing space, unless the next word starts with
 * closing punctuation. Lines that already carry spacing are returned unchanged.
 */
internal fun separateUnspacedWords(texts: List<String>): List<String> {
    if (texts.size < 2 || texts.any { text -> text.any(Char::isWhitespace) }) return texts
    return texts.mapIndexed { index, text ->
        val next = texts.getOrNull(index + 1)
        if (next == null || next.first() in ",.!?;:)]}") text else "$text "
    }
}
