package dev.metrofuse.runtime

import dev.metrofuse.api.LoginCredentials
import dev.metrofuse.api.LoginField
import dev.metrofuse.api.LoginRequest
import dev.metrofuse.api.PluginLoginState
import dev.metrofuse.api.WebLoginRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebLoginTest {
    private val browser =
        WebLoginRequest(
            "https://accounts.google.com/ServiceLogin",
            "https://music.youtube.com",
            "cookie",
            listOf("SAPISID", "__Secure-3PAPISID"),
            listOf("music.youtube.com", "accounts.google.com"),
        )
    private val form = LoginRequest(listOf(LoginField("cookie", "Session")))

    @Test
    fun browserCaptureRequiresTrustedHttpsAndSessionCookies() {
        browser.validate(form)
        assertTrue(browser.acceptsNavigation(browser.url))
        for (url in listOf(
            "http://music.youtube.com",
            "https://music.youtube.com.evil.test",
            "https://user@music.youtube.com",
            "https://music.youtube.com:444",
            "file:///tmp/login",
        )) {
            assertFalse(browser.acceptsNavigation(url))
        }
        assertNull(browser.captureCookies("VISITOR_INFO1_LIVE=guest"))
        assertNull(browser.captureCookies("SAPISID=invalid\r\nheader"))
        assertEquals(mapOf("cookie" to "__Secure-3PAPISID=session"), browser.captureCookies("__Secure-3PAPISID=session"))
        assertFails { browser.copy(cookieUrl = "https://other.test").validate(form) }
        assertFails { browser.copy(cookieField = "other").validate(form) }
    }

    @Test
    fun staleWebViewCannotSubmitCredentialsToAnotherActivation() =
        runBlocking<Unit> {
            val data = Files.createTempDirectory("metrofuse-web-login-test-").toFile()
            var calls = 0
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val logins = PluginLogins(scope, testCredentialStore(data.resolve("credentials")))

            fun bind(token: String) =
                logins.bind(
                    "fixture.web",
                    token,
                    MutableStateFlow(PluginLoginState()),
                    request = { form },
                    login = { _: LoginCredentials ->
                        calls++
                        PluginLoginState(loggedIn = true, accountName = "Fixture")
                    },
                    logout = {},
                    webRequest = { browser },
                )
            try {
                bind("previous")
                logins.request("fixture.web")
                withTimeout(5000) { logins.prompt.first { it?.token == "previous" } }
                assertEquals(browser, logins.prompt.value?.web)
                bind("current")
                logins.request("fixture.web")
                withTimeout(5000) { logins.prompt.first { it?.token == "current" } }
                logins.submitWeb("fixture.web", "previous", mapOf("cookie" to "SAPISID=previous"))
                assertEquals(0, calls)
                logins.submitWeb("fixture.web", "current", mapOf("cookie" to "SAPISID=current"))
                withTimeout(
                    5000,
                ) { logins.states.first { it["fixture.web"]?.account?.loggedIn == true && it["fixture.web"]?.busy == false } }
                assertEquals(1, calls)
                assertTrue(
                    logins.states.value
                        .getValue("fixture.web")
                        .account.loggedIn,
                )
            } finally {
                logins.unbind("fixture.web")
                scope.cancel()
                data.deleteRecursively()
            }
        }
}
