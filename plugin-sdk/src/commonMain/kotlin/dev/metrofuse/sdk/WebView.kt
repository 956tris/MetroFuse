package dev.metrofuse.sdk

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.metrofuse.api.LoginUiContext
import dev.metrofuse.api.NativeLoginUi
import dev.metrofuse.api.WebLoginRequest
import kotlinx.serialization.Serializable

/** All labels and provider navigation rules belong to the calling plugin. */
@Serializable
data class WebViewLabels(
    val title: String,
    val hint: String,
    val error: String,
    val retry: String,
    val cancel: String,
    val login: String,
    val logout: String,
) {
    init {
        require(title.isNotBlank() && title.length <= 128)
        require(hint.length <= 4096 && error.isNotBlank() && error.length <= 4096)
        require(listOf(retry, cancel, login, logout).all { it.isNotBlank() && it.length <= 128 })
    }
}

@Serializable
data class WebViewLoginUi(
    val request: WebLoginRequest,
    val labels: WebViewLabels,
)

class WebViewLoginScreen(
    private val ui: WebViewLoginUi,
) : NativeLoginUi {
    init {
        ui.request.validateBrowserRequest()
    }

    @Composable
    override fun Content(context: LoginUiContext) {
        if (!context.account.loggedIn && context.ready) {
            WebViewLoginDialog(ui.request, ui.labels, context)
        } else {
            AlertDialog(
                onDismissRequest = context.dismiss,
                title = { Text(ui.labels.title) },
                text = {
                    Column {
                        context.account.accountName?.let { Text(it) }
                        if (context.busy) CircularProgressIndicator()
                        if (context.failed) Text(ui.labels.error)
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !context.busy,
                        onClick = if (context.account.loggedIn) context.logout else context.requestLogin,
                    ) { Text(if (context.account.loggedIn) ui.labels.logout else ui.labels.login) }
                },
                dismissButton = { TextButton(onClick = context.dismiss) { Text(ui.labels.cancel) } },
            )
        }
    }
}

/** SDK-owned platform browser. Plugins own when to show it and the accepted session. */
@Composable
expect fun WebViewLoginDialog(
    request: WebLoginRequest,
    labels: WebViewLabels,
    context: LoginUiContext,
)

expect fun WebLoginRequest.validateBrowserRequest()
