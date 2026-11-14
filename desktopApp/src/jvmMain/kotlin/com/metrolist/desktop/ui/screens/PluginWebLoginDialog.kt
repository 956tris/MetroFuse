package com.metrolist.desktop.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.auth.readWindowsLoginHelperOutput
import com.metrolist.desktop.spotifyimport.ProviderLoginWindow
import com.metrolist.desktop.ui.components.WebViewAvailabilityLoading
import com.metrolist.desktop.ui.components.WebViewUnavailableContent
import com.metrolist.desktop.ui.components.rememberLinuxWebKitAvailable
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.login
import com.metrolist.shared.generated.resources.plugin_login_browser_hint
import com.metrolist.shared.generated.resources.plugin_login_failed
import com.metrolist.shared.generated.resources.try_again
import com.metrolist.shared.playlistimport.awaitProviderCredentials
import com.metrolist.shared.utils.OsUtil
import dev.metrofuse.runtime.PluginAccountState
import dev.metrofuse.runtime.PluginLoginPrompt
import dev.metrofuse.runtime.acceptsNavigation
import dev.metrofuse.runtime.captureCookies
import io.github.kdroidfilter.webview.web.WebView
import io.github.kdroidfilter.webview.web.rememberWebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewState
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.stringResource
import java.io.File
import java.net.URI
import java.nio.file.Files

@Composable
fun PluginWebLoginDialog(
    prompt: PluginLoginPrompt,
    account: PluginAccountState,
    onCredentials: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = stringResource(Res.string.login)
    val webKitAvailable = rememberLinuxWebKitAvailable()
    ProviderLoginWindow(title, onDismiss) {
        when {
            OsUtil.isWindows -> PluginWindowsLogin(prompt, account, title, onCredentials, onDismiss)
            webKitAvailable == null -> WebViewAvailabilityLoading()
            !webKitAvailable -> WebViewUnavailableContent(title = title, onDismissRequest = onDismiss)
            else -> PluginBrowserLogin(prompt, account, onCredentials, onDismiss)
        }
    }
}

@Composable
private fun PluginWindowsLogin(
    prompt: PluginLoginPrompt,
    account: PluginAccountState,
    title: String,
    onCredentials: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val request = checkNotNull(prompt.web)
    val currentSubmit by rememberUpdatedState(onCredentials)
    var attempt by remember(prompt.token) { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(prompt.token, attempt) {
        running = true
        failed = false
        try {
            val resources = System.getProperty("compose.application.resources.dir")
            val helper =
                if (resources != null) {
                    File(resources, "windows/metro-wv2-helper.exe")
                } else {
                    File("desktopApp/src/desktopMain/resources/windows/metro-wv2-helper.exe")
                }
            check(helper.isFile)
            val output =
                readWindowsLoginHelperOutput(
                    ProcessBuilder(helper.absolutePath, "--cookie-config", Json.encodeToString(request), "--title", title),
                )
            val values = Json.parseToJsonElement(output).jsonObject["values"]?.jsonObject
            val header = values?.get(request.cookieField)?.jsonPrimitive?.content
            val credentials = header?.let(request::captureCookies)
            checkNotNull(credentials)
            currentSubmit(credentials)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        } finally {
            running = false
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(stringResource(Res.string.plugin_login_browser_hint))
        if (running || account.busy) CircularProgressIndicator()
        if (failed || account.error != null) {
            Text(stringResource(Res.string.plugin_login_failed))
            TextButton(enabled = !running && !account.busy, onClick = { attempt++ }) { Text(stringResource(Res.string.try_again)) }
        }
        TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
    }
}

@Composable
private fun PluginBrowserLogin(
    prompt: PluginLoginPrompt,
    account: PluginAccountState,
    onCredentials: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val request = checkNotNull(prompt.web)
    val profile = remember(prompt.token) { Files.createTempDirectory("metrofuse-plugin-web-login-").toFile() }
    val browser =
        rememberWebViewState(request.url).apply {
            webSettings.desktopWebSettings.dataDirectory = profile.absolutePath
            webSettings.customUserAgentString = buildYouTubeBrowserUserAgent()
        }
    val navigator = rememberWebViewNavigator()
    val currentSubmit by rememberUpdatedState(onCredentials)
    var attempt by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(profile) {
        onDispose {
            // Native WebKit may release profile files after the Compose window finishes closing.
            Thread {
                repeat(40) {
                    if (!profile.exists() || profile.deleteRecursively()) return@Thread
                    Thread.sleep(250)
                }
            }.apply { isDaemon = true }.start()
        }
    }
    LaunchedEffect(prompt.token, attempt) {
        failed = false
        try {
            val values =
                awaitProviderCredentials {
                    val current = browser.lastLoadedUrl
                    if (current == null || !request.acceptsNavigation(current) || URI(current).host != URI(request.cookieUrl).host) {
                        null
                    } else {
                        request.captureCookies(
                            browser.cookieManager.getCookies(request.cookieUrl).joinToString("; ") { "${it.name}=${it.value}" },
                        )
                    }
                }
            currentSubmit(values)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            failed = true
        }
    }
    Column(Modifier.fillMaxSize()) {
        WebView(browser, navigator = navigator, modifier = Modifier.fillMaxWidth().weight(1f))
        if (account.busy) CircularProgressIndicator()
        Text(stringResource(if (failed || account.error != null) Res.string.plugin_login_failed else Res.string.plugin_login_browser_hint))
        if (failed ||
            account.error != null
        ) {
            TextButton(enabled = !account.busy, onClick = { attempt++ }) { Text(stringResource(Res.string.try_again)) }
        }
        TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
    }
}
