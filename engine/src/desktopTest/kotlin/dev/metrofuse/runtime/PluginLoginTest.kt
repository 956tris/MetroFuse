package dev.metrofuse.runtime

import androidx.compose.runtime.Composable
import dev.metrofuse.api.LoginCredentials
import dev.metrofuse.api.LoginUiContext
import dev.metrofuse.api.Manifest
import dev.metrofuse.api.NativeLoginUi
import dev.metrofuse.api.PluginLoginState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PluginLoginTest {
    @Test
    fun accountUiFailureAndTimeoutFinishWithSanitizedState() =
        runBlocking<Unit> {
            val logins = PluginLogins(this, UnavailableCredentialStore, timeoutMs = 50)
            try {
                for (uiFactory in listOf<suspend () -> NativeLoginUi?>(
                    { throw AssertionError("fixture-secret") },
                    {
                        delay(500)
                        null
                    },
                )) {
                    logins.bind(
                        "fixture.ui",
                        "activation",
                        MutableStateFlow(PluginLoginState()),
                        request = { null },
                        login = { PluginLoginState() },
                        logout = {},
                        accountUi = uiFactory,
                    )
                    val state = withTimeout(1000) { logins.states.first { it["fixture.ui"]?.uiFailed == true } }.getValue("fixture.ui")
                    assertFalse(state.uiLoading)
                    assertEquals(LoginFailure.REQUEST, state.error)
                    assertNull(state.ui)
                }
            } finally {
                logins.unbind("fixture.ui")
            }
        }

    @Test
    fun loginStatusChangedBeforeCollectionStartsIsObserved() =
        runBlocking<Unit> {
            val status = MutableStateFlow(PluginLoginState())
            val logins = PluginLogins(this, UnavailableCredentialStore)
            try {
                logins.bind("fixture.early", "activation", status, request = { null }, login = { status.value }, logout = {})
                status.value = PluginLoginState(loggedIn = true)
                withTimeout(1000) { logins.states.first { it["fixture.early"]?.account?.loggedIn == true } }
            } finally {
                logins.unbind("fixture.early")
            }
        }

    @Test
    fun fatalPluginErrorDuringCredentialRestoreIsIsolated() =
        runBlocking<Unit> {
            val credentials =
                object : CredentialStore {
                    override suspend fun read(owner: String) = LoginCredentials(mapOf("token" to "fixture-secret"))

                    override suspend fun write(
                        owner: String,
                        credentials: LoginCredentials?,
                    ) {}
                }
            val logins = PluginLogins(this, credentials)
            val ui =
                object : NativeLoginUi {
                    @Composable
                    override fun Content(context: LoginUiContext) {}
                }
            try {
                logins.bind(
                    "fixture.restore",
                    "activation",
                    MutableStateFlow(PluginLoginState()),
                    request = { null },
                    login = { throw AssertionError("fixture-secret") },
                    logout = {},
                    accountUi = { ui },
                )
                withTimeout(1000) {
                    logins.states.first {
                        it["fixture.restore"]?.let { state ->
                            state.error == LoginFailure.STORAGE &&
                                !state.uiLoading
                        } ==
                            true
                    }
                }
                assertSame(
                    ui,
                    logins.states.value
                        .getValue("fixture.restore")
                        .ui,
                )
                assertFalse(
                    logins.states.value
                        .getValue("fixture.restore")
                        .busy,
                )
            } finally {
                logins.unbind("fixture.restore")
            }
        }

    @Test
    fun returnedLoginStateCanExpireThroughReactiveUpdates() =
        runBlocking {
            fixture { runtime ->
                install(runtime, "fixture.expiry", "return {loggedIn:true,accountName:'Fixture'};")
                runtime.enable("fixture.expiry")
                runtime.logins.request("fixture.expiry")
                withTimeout(5000) { runtime.logins.prompt.first { it?.owner == "fixture.expiry" } }
                runtime.logins.submit("fixture.expiry", mapOf("token" to "fixture-secret"))
                withTimeout(5000) {
                    runtime.logins.states.first {
                        it["fixture.expiry"]?.let { state -> state.account.loggedIn && !state.busy } ==
                            true
                    }
                }
                runtime.dispatchAction("fixture.expiry", "expire")
                withTimeout(5000) { runtime.logins.states.first { it["fixture.expiry"]?.account?.loggedIn == false } }
            }
        }

    @Test
    fun encryptedCredentialsSurviveRestartAndCannotBeMovedToAnotherOwner() =
        runBlocking {
            assumeTrue(System.getProperty("os.name").startsWith("Windows"))
            val directory = Files.createTempDirectory("plugin-credentials-").toFile()
            try {
                val store = desktopCredentialStore(directory)
                store.write("fixture.one", LoginCredentials(mapOf("token" to "fixture-secret-one")))
                store.write("fixture.two", LoginCredentials(mapOf("token" to "fixture-secret-two")))
                assertEquals("fixture-secret-one", desktopCredentialStore(directory).read("fixture.one")?.values?.get("token"))
                val files = directory.listFiles()!!.toList()
                assertTrue(files.all { !it.readBytes().decodeToString().contains("fixture-secret") })
                val original = files.map { it.readBytes() }
                files[0].writeBytes(original[1])
                files[1].writeBytes(original[0])
                assertFails { store.read("fixture.one") }
                assertFails { store.read("fixture.two") }
                store.write("fixture.one", null)
                assertNull(store.read("fixture.one"))
                assertEquals("LoginCredentials([redacted])", LoginCredentials(mapOf("token" to "fixture-secret")).toString())
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun quickJsLoginIsOwnerScopedReactiveAndRestoredAfterReload() =
        runBlocking {
            fixture { runtime ->
                install(runtime, "fixture.one")
                install(runtime, "fixture.two")
                runtime.enable("fixture.one")
                runtime.enable("fixture.two")
                runtime.logins.request("fixture.one")
                withTimeout(5000) { runtime.logins.prompt.first { it?.owner == "fixture.one" } }
                runtime.logins.submit("fixture.one", mapOf("token" to "fixture-secret"))
                withTimeout(5000) {
                    runtime.logins.states.first {
                        it["fixture.one"]?.let { state -> state.account.loggedIn && !state.busy } ==
                            true
                    }
                }
                assertFalse(
                    runtime.logins.states.value
                        .getValue("fixture.two")
                        .account.loggedIn,
                )
                runtime.hotReplace("fixture.one")
                withTimeout(5000) {
                    runtime.logins.states.first {
                        it["fixture.one"]?.let { state -> state.account.loggedIn && !state.busy } ==
                            true
                    }
                }
                runtime.logins.logout("fixture.one")
                withTimeout(5000) {
                    runtime.logins.states.first {
                        it["fixture.one"]?.let { state -> !state.account.loggedIn && !state.busy } ==
                            true
                    }
                }
                runtime.disable("fixture.one")
                assertFalse(
                    runtime.logins.states.value
                        .containsKey("fixture.one"),
                )
                assertNull(runtime.logins.prompt.value)
            }
        }

    @Test
    fun quickJsExceptionsAndInfiniteLoginDoNotExposeCredentialsOrCrashTheHost() =
        runBlocking {
            for (body in listOf("throw Error(credentials.values.token)", "while(true){}")) {
                fixture { runtime ->
                    install(runtime, "fixture.failure", body)
                    runtime.enable("fixture.failure")
                    runtime.logins.request("fixture.failure")
                    withTimeout(5000) { runtime.logins.prompt.first { it?.owner == "fixture.failure" } }
                    runtime.logins.submit("fixture.failure", mapOf("token" to "fixture-secret"))
                    withTimeout(5000) {
                        runtime.logins.states.first {
                            it["fixture.failure"]?.let { state ->
                                !state.busy &&
                                    state.error == LoginFailure.AUTHENTICATION
                            } ==
                                true
                        }
                    }
                    assertFalse(runtime.logs.value.any { "fixture-secret" in it })
                    assertEquals(
                        "Enabled",
                        runtime.plugins.value
                            .single()
                            .state,
                    )
                }
            }
        }

    private suspend fun fixture(block: suspend (Runtime) -> Unit) {
        val directory = Files.createTempDirectory("plugin-login-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime =
            Runtime(
                File(directory, "runtime"),
                "desktop",
                scope,
                EngineTestHost(),
                credentialStore = testCredentialStore(File(directory, "credentials")),
            )
        try {
            runtime.initialize()
            block(runtime)
        } finally {
            runtime.close()
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    private suspend fun install(
        runtime: Runtime,
        id: String,
        body: String =
            "const state={loggedIn:credentials.values.token==='fixture-secret',accountName:'Fixture'};" +
                "host.loginState(state);return state;",
    ) {
        val manifest =
            Manifest(id, "Login fixture", "1.0.0", "Tests", runtime = "javascript", script = "index.js", capabilities = listOf("login"))
        val script =
            """
            globalThis.plugin={async init(host){
              host.action('expire',()=>host.loginState({loggedIn:false}));
              host.login({request:()=>({fields:[{id:'token',label:'Token'}]}),
                login:credentials=>{$body},logout:()=>host.loginState({loggedIn:false})});
            }};
            """.trimIndent()
        val bytes =
            ByteArrayOutputStream().use { output ->
                ZipOutputStream(output).use { zip ->
                    for ((name, value) in mapOf("manifest.json" to codec.encodeToString(manifest), "index.js" to script)) {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(value.encodeToByteArray())
                        zip.closeEntry()
                    }
                }
                output.toByteArray()
            }
        runtime.install(ByteArrayInputStream(bytes))
    }
}
