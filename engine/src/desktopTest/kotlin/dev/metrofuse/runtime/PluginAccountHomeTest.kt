package dev.metrofuse.runtime

import dev.metrofuse.api.HomeRequest
import dev.metrofuse.api.Manifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertTrue

class PluginAccountHomeTest {
    @Test
    fun successfulLoginAndLogoutRefreshAnAlreadyLoadedHome() =
        runBlocking<Unit> {
            val data = Files.createTempDirectory("metrofuse-account-home-").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runtime =
                Runtime(data, "desktop", scope, EngineTestHost(), credentialStore = testCredentialStore(data.resolve("credentials")))
            try {
                runtime.initialize()
                val manifest =
                    Manifest(
                        OWNER,
                        "Account Home fixture",
                        "1.0.0",
                        "tests",
                        runtime = "javascript",
                        script = "index.js",
                        capabilities = listOf("home", "login"),
                    )
                val bytes = ByteArrayOutputStream()
                ZipOutputStream(bytes).use { archive ->
                    for ((name, value) in mapOf("manifest.json" to codec.encodeToString(manifest), "index.js" to SCRIPT)) {
                        archive.putNextEntry(ZipEntry(name))
                        archive.write(value.toByteArray())
                        archive.closeEntry()
                    }
                }
                runtime.install(ByteArrayInputStream(bytes.toByteArray()), "fixture.mfp")
                runtime.enable(OWNER)
                runtime.homes.refresh(OWNER, HomeRequest("fr", "FR"))
                withTimeout(5000) { runtime.homes.pages.first { it[OWNER]?.loaded == true } }
                assertTrue(
                    runtime.homes.pages.value
                        .getValue(OWNER)
                        .shelves
                        .isEmpty(),
                )
                runtime.logins.request(OWNER)
                withTimeout(5000) { runtime.logins.prompt.first { it?.owner == OWNER } }
                runtime.logins.submit(OWNER, mapOf("token" to "fixture-account"))
                withTimeout(5000) { runtime.logins.states.first { it[OWNER]?.account?.loggedIn == true && it[OWNER]?.busy == false } }
                withTimeout(5000) { runtime.homes.pages.first { it[OWNER]?.loaded == true && it[OWNER]?.shelves?.isNotEmpty() == true } }
                runtime.logins.logout(OWNER)
                withTimeout(5000) { runtime.logins.states.first { it[OWNER]?.account?.loggedIn == false && it[OWNER]?.busy == false } }
                withTimeout(5000) { runtime.homes.pages.first { it[OWNER]?.loaded == true && it[OWNER]?.shelves?.isEmpty() == true } }
            } finally {
                runtime.close()
                scope.cancel()
                data.deleteRecursively()
            }
        }

    companion object {
        private const val OWNER = "fixture.account-home"
        private val SCRIPT =
            """
            let loggedIn=false;
            globalThis.plugin={init(host){
              host.login({request:async()=>({fields:[{id:'token',label:'Fixture token',secret:true}]}),
                login:async credentials=>{
                  loggedIn=true;
                  const state={loggedIn:true,accountName:credentials.values.token};
                  host.loginState(state);return state;
                },
                logout:async()=>{loggedIn=false;host.loginState({loggedIn:false})}});
              host.home(async()=>loggedIn ? [
                {title:'Personal recommendations',items:[{track:{id:'one',title:'One',artist:'',album:''}}]}
              ] : []);
            }};
            """.trimIndent()
    }
}
