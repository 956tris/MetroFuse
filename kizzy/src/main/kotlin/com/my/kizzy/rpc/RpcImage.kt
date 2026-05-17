/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * RpcImage.kt is part of Kizzy
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

package com.my.kizzy.rpc

import kotlinx.coroutines.delay

/**
 * Modified by Zion Huang
 */
sealed class RpcImage {
    abstract suspend fun resolveImage(resolveExternalImage: suspend (String) -> String?): String?

    class DiscordImage(val image: String) : RpcImage() {
        override suspend fun resolveImage(resolveExternalImage: suspend (String) -> String?): String {
            return if (image.startsWith("http")) image else "mp:${image}"
        }
    }

    class ExternalImage(
        val image: String,
        private val fallbackDiscordAsset: String? = null,
        private val cacheFailures: Boolean = true,
        private val resolveAttempts: Int = 1,
        private val resolveRetryDelayMs: Long = 500L,
        private val allowRawUrlFallback: Boolean = true,
    ) : RpcImage() {
        override suspend fun resolveImage(resolveExternalImage: suspend (String) -> String?): String? {
            val asset = ArtworkCache.getOrFetch(image, cacheFailures = cacheFailures) {
                resolveExternalWithRetry(resolveExternalImage)
            }
            return when {
                asset != null -> asset.toPresenceImage()
                fallbackDiscordAsset != null -> ArtworkCache
                    .getOrFetch(fallbackDiscordAsset) { resolveExternalImage(fallbackDiscordAsset) }
                    ?.toPresenceImage()
                    ?: fallbackDiscordAsset.takeIf { it.startsWith("http") }?.toPresenceImage()
                image.startsWith("http") && allowRawUrlFallback -> image // Raw URL
                else -> null
            }
        }

        private suspend fun resolveExternalWithRetry(resolveExternalImage: suspend (String) -> String?): String? {
            repeat(resolveAttempts.coerceAtLeast(1)) { attempt ->
                resolveExternalImage(image)?.let { return it }
                if (attempt < resolveAttempts - 1) delay(resolveRetryDelayMs)
            }
            return null
        }

        private fun String.toPresenceImage(): String =
            if (startsWith("http") || startsWith("mp:")) this else "mp:$this"
    }
}
