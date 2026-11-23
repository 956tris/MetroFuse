package com.metrolist.shared.utils

import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

actual suspend fun clearWebCookies() =
    withContext(Dispatchers.Main) {
        val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull()
        if (cookieManager != null) {
            suspendCancellableCoroutine<Unit> { continuation ->
                runCatching {
                    cookieManager.removeAllCookies {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }.onFailure {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
            runCatching { cookieManager.flush() }
        }
        try {
            WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {
        }
    }
