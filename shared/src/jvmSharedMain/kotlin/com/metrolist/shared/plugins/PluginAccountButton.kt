package com.metrolist.shared.plugins

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.account
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.login
import com.metrolist.shared.generated.resources.logout
import com.metrolist.shared.generated.resources.plugin_login_browser_hint
import com.metrolist.shared.generated.resources.plugin_login_failed
import com.metrolist.shared.generated.resources.try_again
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.settings.SettingsNavigationRow
import dev.metrofuse.api.LoginUiContext
import dev.metrofuse.sdk.WebViewLabels
import dev.metrofuse.sdk.WebViewLoginScreen
import dev.metrofuse.sdk.WebViewLoginUi
import org.jetbrains.compose.resources.stringResource

@Composable
fun PluginAccountMenuSection(focusModifier: @Composable (Shape) -> Modifier = { Modifier }) {
    PluginAccountButton { label, onClick ->
        SettingsNavigationRow(
            title = label,
            icon = MetrolistIcons.Outlined.AccountCircle,
            first = true,
            last = true,
            onClick = onClick,
            focusModifier = focusModifier,
        )
    }
}

@Composable
fun PluginAccountButton(
    modifier: Modifier = Modifier,
    content: (@Composable (String, () -> Unit) -> Unit)? = null,
) {
    if (!MetroFuseHost.isConfigured) return
    val runtime = MetroFuseHost.runtime
    val plugins by runtime.plugins.collectAsState()
    val frontends by PluginServicesStore.frontends.collectAsState()
    val owner = remember(Settings.shared.musicFrontendOwner, frontends) { effectiveMusicFrontendOwner() }
    val plugin = plugins.firstOrNull { it.manifest.id == owner && it.state == "Enabled" && "login" in it.manifest.capabilities } ?: return
    val states by runtime.logins.states.collectAsState()
    val prompt by runtime.logins.prompt.collectAsState()
    val state = states[owner] ?: return
    val token = runtime.sessionToken(owner) ?: return
    var expanded by remember(owner, token) { mutableStateOf(false) }
    val currentPrompt = prompt?.takeIf { it.owner == owner }
    val values = remember(currentPrompt) { mutableStateMapOf<String, String>() }
    DisposableEffect(owner, token) {
        onDispose { if (runtime.sessionToken(owner) == token) runtime.logins.dismiss(owner) }
    }
    val label = stringResource(if (state.account.loggedIn) Res.string.account else Res.string.login)
    val openAccount = {
        if (runtime.sessionToken(owner) == token) {
            expanded = true
            if (!state.account.loggedIn) runtime.logins.request(owner)
        }
    }
    if (content != null) {
        content(label, openAccount)
    } else {
        IconButton(modifier = modifier, onClick = openAccount) {
            Icon(MetrolistIcons.Outlined.AccountCircle, label)
        }
    }
    if (expanded) {
        val dismiss = {
            expanded = false
            runtime.logins.dismiss(owner)
        }
        val browserPrompt = currentPrompt?.takeIf { it.web != null && !state.account.loggedIn }
        val uiContext =
            LoginUiContext(
                account = state.account,
                busy = state.busy,
                failed = state.error != null,
                ready = currentPrompt?.token == token,
                requestLogin = { if (runtime.sessionToken(owner) == token) runtime.logins.request(owner) },
                submit = { if (runtime.sessionToken(owner) == token) runtime.logins.submit(owner, it, token) },
                logout = { if (runtime.sessionToken(owner) == token) runtime.logins.logout(owner) },
                dismiss = { if (runtime.sessionToken(owner) == token) dismiss() },
            )
        val accountUi = state.ui
        if (accountUi != null) {
            accountUi.Content(uiContext)
        } else if (browserPrompt != null) {
            WebViewLoginScreen(
                WebViewLoginUi(
                    checkNotNull(browserPrompt.web),
                    WebViewLabels(
                        title = plugin.manifest.name,
                        hint = stringResource(Res.string.plugin_login_browser_hint),
                        error = stringResource(Res.string.plugin_login_failed),
                        retry = stringResource(Res.string.try_again),
                        cancel = stringResource(Res.string.cancel),
                        login = stringResource(Res.string.login),
                        logout = stringResource(Res.string.logout),
                    ),
                ),
            ).Content(uiContext)
        } else {
            AlertDialog(
                onDismissRequest = uiContext.dismiss,
                title = { Text(plugin.manifest.name) },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        state.account.accountName?.let { Text(it) }
                        if (state.busy || state.uiLoading) CircularProgressIndicator()
                        if (state.error != null) Text(stringResource(Res.string.plugin_login_failed))
                        if (!state.uiLoading && !state.uiFailed && !state.account.loggedIn && currentPrompt != null) {
                            if (currentPrompt.request.instructions.isNotBlank()) Text(currentPrompt.request.instructions)
                            currentPrompt.request.fields.forEach { field ->
                                OutlinedTextField(
                                    value = values[field.id].orEmpty(),
                                    onValueChange = { values[field.id] = it.take(16384) },
                                    label = { Text(field.label) },
                                    singleLine = true,
                                    enabled = !state.busy,
                                    visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    if (!state.uiLoading && !state.uiFailed) {
                        when {
                            state.account.loggedIn -> {
                                TextButton(
                                    enabled = !state.busy,
                                    onClick = uiContext.logout,
                                ) { Text(stringResource(Res.string.logout)) }
                            }

                            currentPrompt != null -> {
                                TextButton(
                                    enabled =
                                        !state.busy && currentPrompt.request.fields.all { !values[it.id].isNullOrBlank() },
                                    onClick = { uiContext.submit(values.toMap()) },
                                ) { Text(stringResource(Res.string.login)) }
                            }

                            else -> {
                                TextButton(
                                    enabled = !state.busy,
                                    onClick = uiContext.requestLogin,
                                ) { Text(stringResource(Res.string.try_again)) }
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onClick = uiContext.dismiss) { Text(stringResource(Res.string.cancel)) } },
            )
        }
    }
}
