/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils.instagram

private val RequiredCookieNames = setOf("sessionid", "ds_user_id")

fun normalizeInstagramCookieInput(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return null

    val cookies = parseInstagramCookies(trimmed)
    if (!RequiredCookieNames.all { !cookies[it].isNullOrBlank() }) return null

    return cookies.entries
        .joinToString("; ") { (name, value) -> "$name=$value" }
}

fun mergeInstagramCookieInputs(inputs: Iterable<String>): String? =
    normalizeInstagramCookieInput(
        inputs
            .flatMap { it.split('\n', '\r') }
            .joinToString("; "),
    )

fun isInstagramCookieConfigured(value: String): Boolean =
    normalizeInstagramCookieInput(value) != null

fun parseInstagramCookies(input: String): Map<String, String> {
    return input
        .split(';', '\n', '\r')
        .mapNotNull { part ->
            val index = part.indexOf('=')
            if (index <= 0) return@mapNotNull null
            val name = part.substring(0, index).trim()
            val value = part.substring(index + 1).trim().trim('"', '\'')
            if (name.isBlank() || value.isBlank()) return@mapNotNull null
            name to value
        }
        .toMap()
}
