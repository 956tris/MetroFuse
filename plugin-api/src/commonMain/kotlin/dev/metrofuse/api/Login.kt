package dev.metrofuse.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
data class PluginLoginState(
    val loggedIn: Boolean = false,
    val accountName: String? = null,
)

@Serializable
data class LoginField(
    val id: String,
    val label: String,
    val secret: Boolean = true,
)

/** A host-rendered form. Tokens and cookies never enter ordinary plugin settings. */
@Serializable
data class LoginRequest(
    val fields: List<LoginField>,
    val instructions: String = "",
)

@Serializable
class LoginCredentials(
    val values: Map<String, String>,
) {
    override fun toString(): String = "LoginCredentials([redacted])"
}

private val signedOut: StateFlow<PluginLoginState> = MutableStateFlow(PluginLoginState())

/** Optional companion interface, preserving the ABI of existing NativePlugin binaries. */
interface LoginPlugin {
    val loginState: StateFlow<PluginLoginState> get() = signedOut

    fun isLoggedIn(): Boolean = loginState.value.loggedIn

    suspend fun loginRequest(): LoginRequest? = null

    suspend fun login(credentials: LoginCredentials): PluginLoginState = PluginLoginState()

    suspend fun logout() {}
}
