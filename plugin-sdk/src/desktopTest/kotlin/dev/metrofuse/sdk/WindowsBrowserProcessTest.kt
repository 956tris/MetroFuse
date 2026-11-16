package dev.metrofuse.sdk

import dev.metrofuse.api.WebLoginRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsBrowserProcessTest {
    private val request =
        WebLoginRequest(
            url = "https://accounts.example.com/login?continue=https%3A%2F%2Fmusic.example.com&label=%22音楽%22",
            cookieUrl = "https://music.example.com",
            cookieField = "session",
            requiredCookies = listOf("AUTH"),
            allowedHosts = listOf("accounts.example.com", "music.example.com"),
        )

    @Test
    fun publishedHelperReadsUtf8JsonThroughTheWindowsProcessBoundary(): Unit =
        runBlocking {
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val directory = Files.createTempDirectory("SDK browser request ").toFile()
            try {
                val requestFile = directory.resolve("request with spaces.json")
                requestFile.writeText(Json.encodeToString(request), Charsets.UTF_8)
                val output =
                    withTimeout(30_000) {
                        readSdkBrowserOutput(executable(), requestFile, "音楽 login", directory.resolve("profile"), validateOnly = true)
                    }
                assertEquals(request, Json.decodeFromString<WebLoginRequest>(output))
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun malformedRequestReturnsAnErrorWithoutOpeningACrashDialog(): Unit =
        runBlocking {
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val directory = Files.createTempDirectory("SDK browser invalid request ").toFile()
            try {
                val requestFile = directory.resolve("request.json")
                requestFile.writeText("{url:invalid}", Charsets.UTF_8)
                withTimeout(30_000) {
                    assertFailsWith<IllegalStateException> {
                        readSdkBrowserOutput(executable(), requestFile, "SDK", directory.resolve("profile"), validateOnly = true)
                    }
                }
            } finally {
                directory.deleteRecursively()
            }
        }

    private fun executable() = File(checkNotNull(System.getProperty("metrofuse.sdk.browserExecutable")))

    @Test
    fun helperFailuresReportDiagnosticsWithoutEchoingPrivateRequestData(): Unit =
        runBlocking {
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val directory = Files.createTempDirectory("SDK browser diagnostic ").toFile()
            val diagnostics = CopyOnWriteArrayList<String>()
            try {
                val requestFile = directory.resolve("request.json")
                requestFile.writeText("{\"private\":\"fixture-private-value\",broken}", Charsets.UTF_8)
                withTimeout(30_000) {
                    assertFailsWith<IllegalStateException> {
                        readSdkBrowserOutput(
                            executable(),
                            requestFile,
                            "SDK",
                            directory.resolve("profile"),
                            validateOnly = true,
                            onDiagnostic = diagnostics::add,
                        )
                    }
                }
                assertTrue(diagnostics.any { "event=startup_failed" in it })
                assertFalse(diagnostics.any { "fixture-private-value" in it || "request.json" in it })
            } finally {
                directory.deleteRecursively()
            }
        }
}
