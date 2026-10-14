/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils.deezer

import java.net.URLDecoder

fun normalizeDeezerCookieInput(input: String): String? =
    mergeDeezerCookieInputs(listOf(input))

fun mergeDeezerCookieInputs(inputs: Iterable<String>): String? {
    val candidates =
        inputs
            .flatMap(::deezerCookieCandidates)
            .filter { it.isNotBlank() }

    val cookies = linkedMapOf<String, String>()
    candidates
        .flatMap(::parseCookiePairs)
        .forEach { pair ->
            cookies[pair.name] = pair.value
        }

    candidates
        .firstOrNull { candidate -> candidate.isPlausibleRawArl() }
        ?.let { cookies.putIfAbsent("arl", it) }

    if (cookies["arl"].isNullOrBlank()) return null

    val ordered = linkedMapOf<String, String>()
    listOf("arl", "sid", "dzr_uniq_id", "dzr_prst", "dzr_c", "dz_lang").forEach { name ->
        cookies[name]?.let { ordered[name] = it }
    }
    cookies.forEach { (name, value) ->
        ordered.putIfAbsent(name, value)
    }

    return ordered.entries.joinToString("; ") { (name, value) -> "$name=$value" }
}

fun isDeezerCookieConfigured(value: String): Boolean =
    normalizeDeezerCookieInput(value) != null

/**
 * Normalizes a bare ARL paste (or an `arl=` pair / full header) down to the
 * raw ARL value. Returns null when nothing ARL-shaped is present.
 */
fun normalizeDeezerArlInput(input: String): String? {
    val fromPair = deezerCookieValue(input, "arl")?.trim()
    if (!fromPair.isNullOrBlank()) return fromPair
    val trimmed = input.trim()
    return trimmed.takeIf { it.isPlausibleRawArl() }
}

/** True when [input] contains a usable ARL (bare value or `arl=` pair). */
fun isDeezerArlConfigured(input: String): Boolean =
    normalizeDeezerArlInput(input) != null

/**
 * Merges a freshly pasted [arl] into an already-stored cookie header,
 * preserving companion cookies (sid, …) and dropping nothing else.
 */
fun mergeArlIntoStoredCookie(
    stored: String,
    arl: String,
): String {
    val kept =
        stored
            .split(';', '\n', '\r')
            .mapNotNull { part ->
                val trimmed = part.trim().trim(';')
                if (trimmed.isEmpty()) return@mapNotNull null
                val separator = trimmed.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                val name = trimmed.substring(0, separator).trim()
                val value = trimmed.substring(separator + 1).trim()
                if (name.equals("arl", ignoreCase = true) || value.isBlank()) {
                    null
                } else {
                    "$name=$value"
                }
            }.toMutableList()
    kept.add(0, "arl=$arl")
    return kept.joinToString("; ")
}

fun deezerCookieValue(
    input: String,
    cookieName: String,
): String? =
    deezerCookieCandidates(input)
        .flatMap(::parseCookiePairs)
        .firstOrNull { it.name.equals(cookieName, ignoreCase = true) }
        ?.value

private data class CookiePair(
    val name: String,
    val value: String,
)

private fun deezerCookieCandidates(input: String): List<String> {
    val trimmed =
        input
            .trim()
            .removePrefix("Cookie:")
            .removePrefix("cookie:")
            .trim()
            .trim(';')
    if (trimmed.isBlank()) return emptyList()

    return listOfNotNull(
        trimmed,
        trimmed.replace("\\\"", "\"").replace("\\\\", "\\"),
        trimmed.replace("\\u0022", "\""),
        runCatching { URLDecoder.decode(trimmed, Charsets.UTF_8.name()) }.getOrNull(),
    ).distinct()
}

private fun parseCookiePairs(input: String): List<CookiePair> =
    input
        .trim()
        .removePrefix("Cookie:")
        .removePrefix("cookie:")
        .trim()
        .trim(';')
        .split(';', '\n', '\r')
        .mapNotNull { part ->
            val trimmed = part.trim()
            val separator = trimmed.indexOf('=')
            if (separator <= 0) return@mapNotNull null

            val name = trimmed.substring(0, separator).trim()
            val value = trimmed.substring(separator + 1).trim().trim('"', '\'')
            CookiePair(name, value).takeIf {
                it.name.matches(CookieNameRegex) &&
                    it.name.lowercase() !in CookieAttributeNames &&
                    it.value.isNotBlank()
            }
        }

private fun String.isPlausibleRawArl(): Boolean =
    length >= 100 &&
        none(Char::isWhitespace) &&
        !contains(';') &&
        !contains('=') &&
        !contains('{') &&
        !contains('}')

private val CookieNameRegex = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
private val CookieAttributeNames =
    setOf(
        "domain",
        "expires",
        "max-age",
        "path",
        "priority",
        "samesite",
        "secure",
        "httponly",
    )
