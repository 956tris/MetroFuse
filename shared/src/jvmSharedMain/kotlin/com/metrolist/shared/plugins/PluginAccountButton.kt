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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.account
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.login
import com.metrolist.shared.generated.resources.logout
import com.metrolist.shared.generated.resources.plugin_login_failed
import com.metrolist.shared.generated.resources.try_again
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.stringResource

@Composable
fun PluginAccountButton(modifier: Modifier = Modifier) {
    if (!MetroFuseHost.isConfigured) return
    val runtime = MetroFuseHost.runtime
    val plugins by runtime.plugins.collectAsState()
    val frontends by PluginServicesStore.frontends.collectAsState()
    val owner = remember(Settings.shared.musicFrontendOwner, frontends) { effectiveMusicFrontendOwner() }
    val plugin = plugins.firstOrNull { it.manifest.id == owner && it.state == "Enabled" && "login" in it.manifest.capabilities } ?: return
    val states by runtime.logins.states.collectAsState()
    val prompt by runtime.logins.prompt.collectAsState()
    val state = states[owner] ?: return
    var expanded by remember(owner) { mutableStateOf(false) }
    val currentPrompt = prompt?.takeIf { it.owner == owner }
    val values = remember(currentPrompt) { mutableStateMapOf<String, String>() }
    DisposableEffect(owner) {
        onDispose { runtime.logins.dismiss(owner) }
    }
    val label = stringResource(if (state.account.loggedIn) Res.string.account else Res.string.login)
    IconButton(modifier = modifier, onClick = {
        expanded = true
        if (!state.account.loggedIn) runtime.logins.request(owner)
    }) {
        Icon(MetrolistIcons.Outlined.AccountCircle, label)
    }
    if (expanded) {
        val dismiss = {
            expanded = false
            runtime.logins.dismiss(owner)
        }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(plugin.manifest.name) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    state.account.accountName?.let { Text(it) }
                    if (state.busy) CircularProgressIndicator()
                    if (state.error != null) Text(stringResource(Res.string.plugin_login_failed))
                    if (!state.account.loggedIn && currentPrompt != null) {
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
                when {
                    state.account.loggedIn -> {
                        TextButton(
                            enabled = !state.busy,
                            onClick = { runtime.logins.logout(owner) },
                        ) { Text(stringResource(Res.string.logout)) }
                    }

                    currentPrompt != null -> {
                        TextButton(
                            enabled =
                                !state.busy && currentPrompt.request.fields.all { !values[it.id].isNullOrBlank() },
                            onClick = { runtime.logins.submit(owner, values.toMap()) },
                        ) { Text(stringResource(Res.string.login)) }
                    }

                    else -> {
                        TextButton(
                            enabled = !state.busy,
                            onClick = { runtime.logins.request(owner) },
                        ) { Text(stringResource(Res.string.try_again)) }
                    }
                }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(Res.string.cancel)) } },
        )
    }
}
