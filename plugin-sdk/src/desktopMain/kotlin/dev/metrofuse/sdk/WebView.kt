package dev.metrofuse.sdk

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
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.metrofuse.api.LoginUiContext
import dev.metrofuse.api.WebLoginRequest
import io.github.kdroidfilter.webview.request.RequestInterceptor
import io.github.kdroidfilter.webview.request.WebRequest
import io.github.kdroidfilter.webview.request.WebRequestInterceptResult
import io.github.kdroidfilter.webview.web.WebView
import io.github.kdroidfilter.webview.web.WebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewNavigator
import io.github.kdroidfilter.webview.web.rememberWebViewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.nio.file.Files
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
actual fun WebViewLoginDialog(
    request: WebLoginRequest,
    labels: WebViewLabels,
    context: LoginUiContext,
) {
    DialogWindow(
        onCloseRequest = context.dismiss,
        title = labels.title,
        state = rememberDialogState(width = 800.dp, height = 800.dp),
    ) {
        if (System.getProperty("os.name").startsWith("Windows")) {
            WindowsBrowser(request, labels, context)
        } else {
            var available by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(Unit) { available = withContext(Dispatchers.IO) { isBrowserAvailable() } }
            when (available) {
                true -> {
                    EmbeddedBrowser(request, labels, context)
                }

                else -> {
                    Column(Modifier.padding(24.dp)) {
                        if (available == null) CircularProgressIndicator() else Text(labels.error)
                        TextButton(onClick = context.dismiss) { Text(labels.cancel) }
                    }
                }
            }
        }
    }
}

@Composable
private fun WindowsBrowser(
    request: WebLoginRequest,
    labels: WebViewLabels,
    context: LoginUiContext,
) {
    val submit by rememberUpdatedState(context.submit)
    var attempt by remember(request) { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(request, attempt) {
        running = true
        failed = false
        try {
            val output = runSdkBrowser(request, labels.title)
            val header =
                Json
                    .parseToJsonElement(output)
                    .jsonObject["values"]
                    ?.jsonObject
                    ?.get(request.cookieField)
                    ?.jsonPrimitive
                    ?.content
            submit(checkNotNull(header?.let(request::captureBrowserCookies)))
        } catch (error: CancellationException) {
            if (error !is TimeoutCancellationException) throw error
            failed = true
        } catch (_: Exception) {
            failed = true
        } finally {
            running = false
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(labels.hint)
        if (running || context.busy) CircularProgressIndicator()
        if (failed || context.failed) {
            Text(labels.error)
            TextButton(enabled = !running && !context.busy, onClick = { attempt++ }) { Text(labels.retry) }
        }
        TextButton(onClick = context.dismiss) { Text(labels.cancel) }
    }
}

@Composable
private fun EmbeddedBrowser(
    request: WebLoginRequest,
    labels: WebViewLabels,
    context: LoginUiContext,
) {
    val profile = remember(request) { Files.createTempDirectory("metrofuse-sdk-webview-").toFile() }
    val browser =
        rememberWebViewState(request.url).apply {
            webSettings.desktopWebSettings.dataDirectory = profile.absolutePath
        }
    val navigationRules = request
    val navigator =
        rememberWebViewNavigator(
            requestInterceptor =
                object : RequestInterceptor {
                    override fun onInterceptUrlRequest(
                        request: WebRequest,
                        navigator: WebViewNavigator,
                    ): WebRequestInterceptResult =
                        if (navigationRules.acceptsBrowserNavigation(request.url)) {
                            WebRequestInterceptResult.Allow
                        } else {
                            WebRequestInterceptResult.Reject
                        }
                },
        )
    val submit by rememberUpdatedState(context.submit)
    var attempt by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(profile) {
        onDispose {
            Thread {
                repeat(40) {
                    if (!profile.exists() || profile.deleteRecursively()) return@Thread
                    Thread.sleep(250)
                }
            }.apply { isDaemon = true }.start()
        }
    }
    LaunchedEffect(request, attempt) {
        failed = false
        try {
            submit(
                awaitBrowserCredentials {
                    val url = browser.lastLoadedUrl
                    if (url == null || !request.acceptsBrowserNavigation(url) || URI(url).host != URI(request.cookieUrl).host) {
                        null
                    } else {
                        request.captureBrowserCookies(
                            browser.cookieManager
                                .getCookies(request.cookieUrl)
                                .joinToString("; ") { "${it.name}=${it.value}" },
                        )
                    }
                },
            )
        } catch (error: CancellationException) {
            if (error !is TimeoutCancellationException) throw error
            failed = true
        } catch (_: Exception) {
            failed = true
        }
    }
    Column(Modifier.fillMaxSize()) {
        WebView(browser, navigator = navigator, modifier = Modifier.fillMaxWidth().weight(1f))
        if (context.busy) CircularProgressIndicator()
        Text(if (failed || context.failed) labels.error else labels.hint)
        if (failed || context.failed) TextButton(enabled = !context.busy, onClick = { attempt++ }) { Text(labels.retry) }
        TextButton(onClick = context.dismiss) { Text(labels.cancel) }
    }
}

private object BrowserResources

private suspend fun runSdkBrowser(
    request: WebLoginRequest,
    title: String,
): String =
    withContext(Dispatchers.IO) {
        val directory = Files.createTempDirectory("metrofuse-sdk-browser-").toFile()
        try {
            val executable = directory.resolve("metrofuse-browser.exe")
            checkNotNull(BrowserResources::class.java.getResourceAsStream("/metrofuse-sdk/windows/metrofuse-browser.exe"))
                .use { input -> executable.outputStream().use(input::copyTo) }
            val process =
                ProcessBuilder(executable.absolutePath, Json.encodeToString(request), title, directory.resolve("profile").absolutePath)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            try {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { process.destroyForcibly() }
                    try {
                        val bytes = process.inputStream.use { it.readNBytes(131073) }
                        check(bytes.size <= 131072 && process.waitFor() == 0)
                        continuation.resume(bytes.decodeToString())
                    } catch (error: Exception) {
                        continuation.resumeWithException(error)
                    }
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
        } finally {
            deleteBrowserProfile(directory)
        }
    }

private fun deleteBrowserProfile(directory: File) {
    repeat(40) {
        if (!directory.exists() || directory.deleteRecursively()) return
        Thread.sleep(250)
    }
    directory.deleteOnExit()
}
