package dev.metrofuse.sdk

import dev.metrofuse.api.WebLoginRequest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserSessionMonitorTest {
    private val request = WebLoginRequest(
        url = "https://accounts.example.com/login",
        cookieUrl = "https://music.example.com/",
        cookieField = "cookie",
        requiredCookies = listOf("AUTH"),
        allowedHosts = listOf("accounts.example.com", "music.example.com"),
    )

    @Test
    fun rejectedStaleSessionDoesNotPreventCaptureAfterSignIn() = runTest {
        var header = "AUTH=expired; TRACKING=one"
        var busy = false
        var loggedIn = false
        val submitted = mutableListOf<Map<String, String>>()
        val monitor = launch {
            request.monitorBrowserCredentials(
                capture = { header },
                isBusy = { busy },
                isLoggedIn = { loggedIn },
                submit = { submitted += it; busy = true },
            )
        }
        runCurrent()
        assertEquals(listOf(mapOf("cookie" to header)), submitted)

        // The provider rejects the old cookie while the user is still signing in.
        busy = false
        header = "AUTH=expired; TRACKING=two"
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, submitted.size)

        header = "AUTH=fresh; TRACKING=two"
        advanceTimeBy(500)
        runCurrent()
        assertEquals(mapOf("cookie" to header), submitted.last())
        assertEquals(2, submitted.size)

        // Do not replace the host's verification operation while it is running.
        header = "AUTH=newer"
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, submitted.size)
        loggedIn = true
        advanceTimeBy(500)
        runCurrent()
        assertFalse(monitor.isActive)
    }

    @Test
    fun signInAfterThreeMinutesStillCapturesAndCancellationStopsPolling() = runTest {
        var header: String? = null
        val submitted = mutableListOf<Map<String, String>>()
        val monitor = launch {
            request.monitorBrowserCredentials(
                capture = { header },
                isBusy = { false },
                isLoggedIn = { false },
                submit = { submitted += it },
            )
        }
        advanceTimeBy(240_000)
        runCurrent()
        assertEquals(emptyList(), submitted)
        header = "AUTH=late-approval"
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf(mapOf("cookie" to header)), submitted)
        monitor.cancel()
        runCurrent()
        header = "AUTH=after-dismiss"
        advanceTimeBy(1_000)
        assertEquals(1, submitted.size)
    }
}
