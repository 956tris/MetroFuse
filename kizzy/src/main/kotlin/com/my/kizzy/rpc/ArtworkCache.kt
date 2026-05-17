package com.my.kizzy.rpc

import java.util.concurrent.ConcurrentHashMap

internal object ArtworkCache {
    private const val SUCCESS_TTL_MS = 1000L * 60 * 60 * 24
    private const val FAILURE_TTL_MS = 1000L * 60

    private data class CacheEntry(
        val value: String?,
        val expiresAtMs: Long,
    )

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    suspend fun getOrFetch(
        key: String,
        cacheFailures: Boolean = true,
        fetch: suspend () -> String?,
    ): String? {
        val now = System.currentTimeMillis()
        cache[key]?.takeIf { it.expiresAtMs > now }?.let { return it.value }

        val value = fetch()
        if (value != null || cacheFailures) {
            val ttl = if (value == null) FAILURE_TTL_MS else SUCCESS_TTL_MS
            cache[key] = CacheEntry(value, now + ttl)
        } else {
            cache.remove(key)
        }
        return value
    }
}
