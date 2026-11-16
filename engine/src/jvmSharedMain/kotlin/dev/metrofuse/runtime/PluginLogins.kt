package dev.metrofuse.runtime

import dev.metrofuse.api.LoginCredentials
import dev.metrofuse.api.LoginRequest
import dev.metrofuse.api.NativeLoginUi
import dev.metrofuse.api.PluginLoginState
import dev.metrofuse.api.WebLoginRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class LoginFailure { REQUEST, AUTHENTICATION, STORAGE, LOGOUT }

data class PluginAccountState(
    val account: PluginLoginState = PluginLoginState(),
    val busy: Boolean = false,
    val error: LoginFailure? = null,
    val ui: NativeLoginUi? = null,
    val uiLoading: Boolean = false,
    val uiFailed: Boolean = false,
    val revision: Long = 0L,
)

data class PluginLoginPrompt(
    val owner: String,
    val token: String,
    val request: LoginRequest,
    val web: WebLoginRequest? = null,
)

/** Host-only coordinator. Plugins receive credentials only through their own bound callback. */
class PluginLogins(
    private val scope: CoroutineScope,
    private val credentials: CredentialStore,
    private val timeoutMs: Long = 15_000,
) {
    val states = MutableStateFlow<Map<String, PluginAccountState>>(emptyMap())
    val prompt = MutableStateFlow<PluginLoginPrompt?>(null)
    private val bindings = ConcurrentHashMap<String, Binding>()
    private val accountRevision = AtomicLong()

    private class Binding(
        val token: String,
        val scope: CoroutineScope,
        val request: suspend () -> LoginRequest?,
        val login: suspend (LoginCredentials) -> PluginLoginState,
        val logout: suspend () -> Unit,
        val webRequest: suspend () -> WebLoginRequest?,
    ) {
        val gate = Mutex()
        var operation: Job? = null
    }

    fun bind(
        owner: String,
        token: String,
        state: StateFlow<PluginLoginState>,
        request: suspend () -> LoginRequest?,
        login: suspend (LoginCredentials) -> PluginLoginState,
        logout: suspend () -> Unit,
        webRequest: suspend () -> WebLoginRequest? = { null },
        accountUi: suspend () -> NativeLoginUi? = { null },
    ) {
        unbind(owner)
        val binding =
            Binding(
                token,
                CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job])),
                request,
                login,
                logout,
                webRequest,
            )
        bindings[owner] = binding
        update(owner, binding) {
            PluginAccountState(account = state.value.validated(), uiLoading = true, revision = accountRevision.incrementAndGet())
        }
        binding.scope.launch {
            try {
                val ui = withTimeout(timeoutMs) { accountUi() }
                update(owner, binding) { it.copy(ui = ui, uiLoading = false) }
            } catch (error: CancellationException) {
                if (error !is TimeoutCancellationException) throw error
                update(owner, binding) { it.copy(error = LoginFailure.REQUEST, uiLoading = false, uiFailed = true) }
            } catch (_: Throwable) {
                update(owner, binding) { it.copy(error = LoginFailure.REQUEST, uiLoading = false, uiFailed = true) }
            }
        }
        binding.scope.launch {
            try {
                state.collect { account ->
                    update(owner, binding) {
                        val validated = account.validated()
                        it.copy(
                            account = validated,
                            revision = if (it.account != validated) accountRevision.incrementAndGet() else it.revision,
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                update(owner, binding) { it.copy(error = LoginFailure.AUTHENTICATION) }
            }
        }
        binding.scope.launch {
            binding.gate.withLock {
                try {
                    val saved = credentials.read(owner) ?: return@withLock
                    update(owner, binding) { it.copy(busy = true) }
                    val restored = withTimeout(timeoutMs) { binding.login(saved) }.validated()
                    update(owner, binding) { it.copy(account = restored, busy = false, revision = accountRevision.incrementAndGet()) }
                } catch (error: CancellationException) {
                    if (error !is TimeoutCancellationException) throw error
                    update(owner, binding) { it.copy(account = PluginLoginState(), busy = false, error = LoginFailure.AUTHENTICATION) }
                } catch (_: Throwable) {
                    update(owner, binding) { it.copy(account = PluginLoginState(), busy = false, error = LoginFailure.STORAGE) }
                }
            }
        }
    }

    fun unbind(owner: String) {
        bindings.remove(owner)?.scope?.cancel()
        states.update { it - owner }
        prompt.update { it?.takeUnless { current -> current.owner == owner } }
    }

    fun request(owner: String) =
        operation(owner, LoginFailure.REQUEST) { binding ->
            val request = requireNotNull(withTimeout(timeoutMs) { binding.request() })
            require(request.fields.size in 1..8 && request.instructions.length <= 4096)
            require(
                request.fields
                    .map { it.id }
                    .distinct()
                    .size == request.fields.size,
            )
            require(request.fields.all { it.id.matches(Regex("[A-Za-z0-9._-]{1,64}")) && it.label.length in 1..128 })
            val web = withTimeout(timeoutMs) { binding.webRequest() }?.also { it.validate(request) }
            if (bindings[owner] === binding) prompt.value = PluginLoginPrompt(owner, binding.token, request, web)
        }

    fun submitWeb(
        owner: String,
        token: String,
        values: Map<String, String>,
    ) {
        val current = prompt.value ?: return
        if (current.owner != owner || current.token != token || current.web == null) return
        submit(owner, values, token)
    }

    fun submit(
        owner: String,
        values: Map<String, String>,
        token: String? = null,
    ) {
        val current = prompt.value?.takeIf { it.owner == owner && (token == null || it.token == token) } ?: return
        operation(owner, LoginFailure.AUTHENTICATION) { binding ->
            require(current.token == binding.token && current === prompt.value)
            require(
                values.keys ==
                    current.request.fields
                        .map { it.id }
                        .toSet(),
            )
            require(values.values.all { it.isNotBlank() && it.length <= 16384 })
            check(credentials !== UnavailableCredentialStore)
            val supplied = LoginCredentials(values.toMap())
            val result = withTimeout(timeoutMs) { binding.login(supplied) }.validated()
            check(result.loggedIn)
            if (bindings[owner] !== binding || prompt.value !== current) return@operation
            try {
                credentials.write(owner, supplied)
            } catch (error: Exception) {
                withTimeout(timeoutMs) { binding.logout() }
                throw error
            }
            update(owner, binding) { it.copy(account = result, revision = accountRevision.incrementAndGet()) }
            prompt.compareAndSet(current, null)
        }
    }

    fun logout(owner: String) =
        operation(owner, LoginFailure.LOGOUT) { binding ->
            credentials.write(owner, null)
            update(owner, binding) { it.copy(account = PluginLoginState(), revision = accountRevision.incrementAndGet()) }
            withTimeout(timeoutMs) { binding.logout() }
            update(owner, binding) { it.copy(account = PluginLoginState()) }
            prompt.update { it?.takeUnless { current -> current.owner == owner } }
        }

    fun dismiss(owner: String) {
        bindings[owner]?.operation?.cancel()
        prompt.update { it?.takeUnless { current -> current.owner == owner } }
        bindings[owner]?.let { binding -> update(owner, binding) { it.copy(busy = false, error = null) } }
    }

    private fun operation(
        owner: String,
        failure: LoginFailure,
        block: suspend (Binding) -> Unit,
    ) {
        val binding = bindings[owner] ?: return
        binding.operation?.cancel()
        binding.operation =
            binding.scope.launch {
                binding.gate.withLock {
                    update(owner, binding) { it.copy(busy = true, error = null) }
                    try {
                        block(binding)
                    } catch (error: CancellationException) {
                        if (error !is TimeoutCancellationException) throw error
                        update(owner, binding) { it.copy(error = failure) }
                    } catch (_: Throwable) {
                        // Plugin exceptions may contain secrets. Expose only a host-owned error code.
                        update(owner, binding) { it.copy(error = failure) }
                    } finally {
                        update(owner, binding) { it.copy(busy = false) }
                    }
                }
            }
    }

    private fun update(
        owner: String,
        binding: Binding,
        transform: (PluginAccountState) -> PluginAccountState,
    ) {
        states.update {
            if (bindings[owner] !== binding) it else it + (owner to transform(it[owner] ?: PluginAccountState()))
        }
    }

    private fun PluginLoginState.validated(): PluginLoginState = copy(accountName = accountName?.take(256))
}
