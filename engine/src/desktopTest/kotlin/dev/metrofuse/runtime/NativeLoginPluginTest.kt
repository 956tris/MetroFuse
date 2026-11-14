package dev.metrofuse.runtime

import dev.metrofuse.api.COMPOSE_ABI
import dev.metrofuse.api.HomeLayout
import dev.metrofuse.api.HomeRequest
import dev.metrofuse.api.LoginPlugin
import dev.metrofuse.api.Manifest
import dev.metrofuse.api.NativePlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.Unit
import kotlin.coroutines.Continuation
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class NativeLoginPluginTest {
    @Test
    fun realJarReceivesItsCredentialsAndRestoresTheLoginFlowOnReload() =
        runBlocking<Unit> {
            val directory = Files.createTempDirectory("native-login-").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val store = testCredentialStore(File(directory, "credentials"))
            val runtime = Runtime(File(directory, "runtime"), "desktop", scope, EngineTestHost(), credentialStore = store)
            try {
                runtime.initialize()
                fixture(directory).inputStream().use { runtime.install(it, "login.jar") }
                runtime.enable("fixture.native-login")
                assertEquals(
                    "Enabled",
                    runtime.plugins.value
                        .single()
                        .state,
                )
                runtime.homes.refresh("fixture.native-login", HomeRequest(Locale.getDefault().language, Locale.getDefault().country))
                val home =
                    withTimeout(
                        5000,
                    ) { runtime.homes.pages.first { it["fixture.native-login"]?.loaded == true } }.getValue("fixture.native-login")
                assertEquals(HomeLayout.CARDS, home.shelves.single().layout)
                assertEquals(
                    "metrofuse:fixture.native-login:one",
                    home.shelves
                        .single()
                        .items
                        .single()
                        .track
                        ?.id,
                )
                runtime.logins.request("fixture.native-login")
                withTimeout(5000) { runtime.logins.prompt.first { it?.owner == "fixture.native-login" } }
                runtime.logins.submit("fixture.native-login", mapOf("token" to "native-fixture-token"))
                withTimeout(5000) {
                    runtime.logins.states.first {
                        it["fixture.native-login"]?.let { state ->
                            state.account.loggedIn &&
                                !state.busy
                        } ==
                            true
                    }
                }
                assertEquals("native-fixture-token", store.read("fixture.native-login")?.values?.get("token"))
                assertNull(store.read("fixture.other"))
                runtime.hotReplace("fixture.native-login")
                withTimeout(5000) {
                    runtime.logins.states.first {
                        it["fixture.native-login"]?.let { state ->
                            state.account.loggedIn &&
                                !state.busy
                        } ==
                            true
                    }
                }
                runtime.logins.logout("fixture.native-login")
                withTimeout(5000) {
                    runtime.logins.states.first {
                        it["fixture.native-login"]?.let { state ->
                            !state.account.loggedIn &&
                                !state.busy
                        } ==
                            true
                    }
                }
                assertNull(store.read("fixture.native-login"))
                runtime.uninstall("fixture.native-login")
                assertFalse(
                    runtime.logins.states.value
                        .containsKey("fixture.native-login"),
                )
                assertFalse(
                    runtime.homes.pages.value
                        .containsKey("fixture.native-login"),
                )
                withTimeout(5000) { runtime.homeSources.first { it.isEmpty() } }
            } finally {
                runtime.close()
                scope.cancel()
                directory.deleteRecursively()
            }
        }

    private fun fixture(directory: File): File {
        val classes = File(directory, "classes").apply { mkdir() }
        val source = File(directory, "NativeLogin.java")
        source.writeText(
            """
            package fixture;
            import dev.metrofuse.api.*;
            import kotlin.Unit;
            import kotlin.coroutines.Continuation;
            import kotlinx.coroutines.flow.*;
            import java.util.List;
            public final class NativeLogin implements NativePlugin, LoginPlugin, HomePlugin {
              private final MutableStateFlow<PluginLoginState> state = StateFlowKt.MutableStateFlow(new PluginLoginState(false, null));
              public StateFlow<PluginLoginState> getLoginState() { return state; }
              public boolean isLoggedIn() { return state.getValue().getLoggedIn(); }
              public Object loginRequest(Continuation<? super LoginRequest> completion) { return new LoginRequest(List.of(new LoginField("token", "Native token", true)), ""); }
              public Object login(LoginCredentials credentials, Continuation<? super PluginLoginState> completion) {
                PluginLoginState result = new PluginLoginState("native-fixture-token".equals(credentials.getValues().get("token")), "Native fixture");
                state.setValue(result); return result;
              }
              public Object logout(Continuation<? super Unit> completion) { state.setValue(new PluginLoginState(false, null)); return Unit.INSTANCE; }
              public Object start(PluginContext context, Continuation<? super Unit> completion) { return Unit.INSTANCE; }
              public Object stop(Continuation<? super Unit> completion) { return Unit.INSTANCE; }
              public Object getHome(HomeRequest request, Continuation<? super List<HomeShelf>> completion) {
                Track track = new Track("one", "Native home", "", "", 0L, "", java.util.Map.of());
                HomeItem item = new HomeItem(track, null, null, null);
                return List.of(new HomeShelf("Native shelf", List.of(item), HomeLayout.CARDS));
              }
            }
            """.trimIndent(),
        )
        val classpath =
            (
                System.getProperty("java.class.path").split(File.pathSeparator) +
                    listOf(
                        NativePlugin::class.java,
                        LoginPlugin::class.java,
                        Unit::class.java,
                        Continuation::class.java,
                        MutableStateFlow::class.java,
                    ).map {
                        File(
                            it.protectionDomain.codeSource.location
                                .toURI(),
                        ).absolutePath
                    }
            ).distinct().joinToString(File.pathSeparator)
        assertEquals(
            0,
            ToolProvider.getSystemJavaCompiler().run(
                null,
                null,
                null,
                "-classpath",
                classpath,
                "-d",
                classes.absolutePath,
                source.absolutePath,
            ),
        )
        val manifest =
            Manifest(
                "fixture.native-login",
                "Native login fixture",
                "1.0.0",
                "Tests",
                runtime = "kotlin",
                entryClass = "fixture.NativeLogin",
                composeAbi = COMPOSE_ABI,
                capabilities = listOf("login", "home"),
            )
        return File(directory, "login.jar").also { artifact ->
            JarOutputStream(artifact.outputStream()).use { jar ->
                jar.putNextEntry(JarEntry("manifest.json"))
                jar.write(codec.encodeToString(manifest).encodeToByteArray())
                jar.closeEntry()
                classes.walkTopDown().filter { it.isFile }.forEach { file ->
                    jar.putNextEntry(JarEntry(file.relativeTo(classes).invariantSeparatorsPath))
                    jar.write(file.readBytes())
                    jar.closeEntry()
                }
            }
        }
    }
}
