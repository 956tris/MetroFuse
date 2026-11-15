package dev.metrofuse.api

import androidx.compose.runtime.Composable

/** Optional plugin-owned account UI; existing LoginPlugin binaries remain compatible. */
interface NativeLoginUi {
    @Composable
    fun Content(context: LoginUiContext)
}

class LoginUiContext(
    val account: PluginLoginState,
    val busy: Boolean,
    val failed: Boolean,
    val ready: Boolean,
    val requestLogin: () -> Unit,
    val submit: (Map<String, String>) -> Unit,
    val logout: () -> Unit,
    val dismiss: () -> Unit,
)
