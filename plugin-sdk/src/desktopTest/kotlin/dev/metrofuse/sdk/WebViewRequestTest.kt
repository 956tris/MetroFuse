package dev.metrofuse.sdk

import dev.metrofuse.api.WebLoginRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebViewRequestTest {
    private val request =
        WebLoginRequest(
            url = "https://accounts.example.com/login",
            cookieUrl = "https://music.example.com",
            cookieField = "session",
            requiredCookies = listOf("AUTH", "SECURE_AUTH"),
            allowedHosts = listOf("accounts.example.com", "music.example.com"),
        )

    @Test
    fun navigationAndConfigurationRejectForeignOriginsAndUnsafeSchemes() {
        request.validateBrowserRequest()
        assertTrue(request.acceptsBrowserNavigation("https://music.example.com/library"))
        listOf(
            "http://music.example.com",
            "https://music.example.com.evil.test",
            "https://evil.test",
            "https://user@music.example.com",
            "https://music.example.com:8443",
            "file:///tmp/session",
        ).forEach {
            assertFalse(request.acceptsBrowserNavigation(it), it)
        }
        assertFailsWith<IllegalArgumentException> { request.copy(cookieUrl = "https://evil.test").validateBrowserRequest() }
        assertFailsWith<IllegalArgumentException> { request.copy(cookieField = "bad\nfield").validateBrowserRequest() }
        assertFailsWith<IllegalArgumentException> { request.copy(requiredCookies = emptyList()).validateBrowserRequest() }
    }

    @Test
    fun browserCaptureRequiresDeclaredSessionCookieAndRejectsHeaderInjection() {
        assertNull(request.captureBrowserCookies("OTHER=fixture"))
        assertNull(request.captureBrowserCookies("AUTH="))
        assertNull(request.captureBrowserCookies("AUTH=fixture\r\nInjected: value"))
        assertNull(request.captureBrowserCookies("AUTH=" + "a".repeat(16384)))
        val header = "SECURE_AUTH=fixture; OTHER=fixture"
        assertEquals(mapOf("session" to header), request.captureBrowserCookies(header))
    }

    @Test
    fun dottedSessionNameUsesTheSameNavigationAndCookieChecks() {
        val dotted = request.copy(requiredCookies = listOf("web.sid.example.com"))
        dotted.validateBrowserRequest()
        assertTrue(dotted.canCaptureBrowserCookies("https://music.example.com/library"))
        assertFalse(dotted.canCaptureBrowserCookies("https://evil.test/"))
        assertNull(dotted.captureBrowserCookies("tracking=fixture"))
        assertEquals(mapOf("session" to "web.sid.example.com=fixture"), dotted.captureBrowserCookies("web.sid.example.com=fixture"))
        assertFailsWith<IllegalArgumentException> { dotted.copy(requiredCookies = listOf("web.sid\n")).validateBrowserRequest() }
    }

    @Test
    fun phoneApprovalCompletesWhenServiceCookiesArriveBeforeTheFinalRedirect(): Unit =
        runBlocking {
            val identityPage = "https://accounts.example.com/challenge/phone"
            var polls = 0
            val captured =
                withTimeout(5_000) {
                    awaitBrowserCredentials {
                        polls++
                        if (!request.canCaptureBrowserCookies(identityPage)) {
                            null
                        } else {
                            request.captureBrowserCookies(if (polls < 3) "OTHER=pending" else "SECURE_AUTH=approved")
                        }
                    }
                }
            assertEquals(3, polls)
            assertEquals(mapOf("session" to "SECURE_AUTH=approved"), captured)
            assertFalse(request.canCaptureBrowserCookies("https://accounts.example.com.evil.test/challenge/phone"))
            assertFalse(request.canCaptureBrowserCookies("http://accounts.example.com/challenge/phone"))
            assertFalse(request.copy(completionPath = "/").canCaptureBrowserCookies("https://accounts.example.com/"))
        }

    @Test
    fun completionPathWaitsForTheCookieOriginAndRejectsEmptyCookieHeaders() {
        val redirect =
            request.copy(
                url = "https://music.example.com/login",
                requiredCookies = emptyList(),
                completionPath = "/",
            )
        redirect.validateBrowserRequest()
        assertFalse(redirect.canCaptureBrowserCookies(redirect.url))
        assertFalse(redirect.canCaptureBrowserCookies("https://accounts.example.com/"))
        assertFalse(redirect.canCaptureBrowserCookies("https://music.example.com/login?next=/"))
        assertTrue(redirect.canCaptureBrowserCookies("https://music.example.com/?from=login"))
        assertNull(redirect.captureBrowserCookies("EMPTY="))
        assertEquals(mapOf("session" to "AUTH=fixture"), redirect.captureBrowserCookies("AUTH=fixture"))
        assertFailsWith<IllegalArgumentException> { redirect.copy(completionPath = "/login").validateBrowserRequest() }
        assertFailsWith<IllegalArgumentException> { redirect.copy(completionPath = "//another-host").validateBrowserRequest() }
    }

    @Test
    fun iframeNavigationDoesNotBlockSignInWhileTopLevelOriginsRemainRestricted() {
        assertFalse(request.blocksBrowserNavigation("https://captcha.example.net/challenge", isMainFrame = false))
        assertTrue(request.blocksBrowserNavigation("https://captcha.example.net/challenge", isMainFrame = true))
        assertFalse(request.blocksBrowserNavigation(request.url, isMainFrame = true))
    }
}
