package dev.metrofuse.runtime

import dev.metrofuse.api.LoginCredentials
import dev.metrofuse.api.LoginField
import dev.metrofuse.api.LoginRequest
import dev.metrofuse.api.PluginLoginState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PluginLoginTimeoutTest {
    @Test
    fun coldSignInAndRestoreCanCompleteAfterTheCallbackDeadline() =
        runTest {
            val credentials = MemoryCredentials()
            val logins = PluginLogins(backgroundScope, credentials)
            val owner = "fixture.slow-login"

            fun bind() =
                logins.bind(
                    owner,
                    "activation",
                    MutableStateFlow(PluginLoginState()),
                    request = { LoginRequest(listOf(LoginField("cookie", "Session"))) },
                    login = {
                        delay(20_000)
                        PluginLoginState(loggedIn = true, accountName = "Fixture")
                    },
                    logout = {},
                )
            try {
                bind()
                runCurrent()
                logins.request(owner)
                runCurrent()
                logins.submit(owner, mapOf("cookie" to "fixture-session"))
                runCurrent()
                advanceTimeBy(15_001)
                runCurrent()
                assertTrue(
                    logins.states.value
                        .getValue(owner)
                        .busy,
                )
                assertNull(
                    logins.states.value
                        .getValue(owner)
                        .error,
                )
                advanceTimeBy(5_000)
                runCurrent()
                assertTrue(
                    logins.states.value
                        .getValue(owner)
                        .account.loggedIn,
                )
                assertEquals("fixture-session", credentials.saved?.values?.get("cookie"))
                assertNull(logins.prompt.value)

                logins.unbind(owner)
                bind()
                runCurrent()
                advanceTimeBy(20_001)
                runCurrent()
                val restored = logins.states.value.getValue(owner)
                assertTrue(restored.account.loggedIn)
                assertFalse(restored.busy)
                assertNull(restored.error)
            } finally {
                logins.unbind(owner)
            }
        }

    @Test
    fun loginDeadlineStillCancelsAHangingVerificationWithoutSavingCredentials() =
        runTest {
            val credentials = MemoryCredentials()
            val logins = PluginLogins(backgroundScope, credentials)
            val owner = "fixture.hanging-login"
            try {
                logins.bind(
                    owner,
                    "activation",
                    MutableStateFlow(PluginLoginState()),
                    request = { LoginRequest(listOf(LoginField("cookie", "Session"))) },
                    login = {
                        delay(90_001)
                        PluginLoginState(loggedIn = true)
                    },
                    logout = {},
                )
                runCurrent()
                logins.request(owner)
                runCurrent()
                logins.submit(owner, mapOf("cookie" to "fixture-session"))
                runCurrent()
                advanceTimeBy(90_001)
                runCurrent()
                val failed = logins.states.value.getValue(owner)
                assertFalse(failed.account.loggedIn)
                assertFalse(failed.busy)
                assertEquals(LoginFailure.AUTHENTICATION, failed.error)
                assertNull(credentials.saved)
                assertEquals(owner, logins.prompt.value?.owner)
            } finally {
                logins.unbind(owner)
            }
        }

    private class MemoryCredentials : CredentialStore {
        var saved: LoginCredentials? = null

        override suspend fun read(owner: String) = saved

        override suspend fun write(
            owner: String,
            credentials: LoginCredentials?,
        ) {
            saved = credentials
        }
    }
}
