package dev.metrofuse.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YouTubeMusicAccountsTest {
    @Test
    fun separateNativeAndUniversalPackagesExposeAccountsAndHomeAcrossEnableAndDisable() =
        runBlocking {
            val files = listOf("metrofuse.ytm.native", "metrofuse.ytm.mfp").map { File(System.getProperty(it).orEmpty()) }
            assumeTrue("Build the separate YouTube Music repository and provide its artifacts", files.all(File::isFile))
            for (artifact in files) {
                val data = Files.createTempDirectory("metrofuse-ytm-accounts-").toFile()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val runtime = Runtime(data, "desktop", scope, EngineTestHost())
                try {
                    runtime.initialize()
                    artifact.inputStream().use { runtime.install(it, artifact.name) }
                    assertFalse(
                        runtime.logins.states.value
                            .containsKey(OWNER),
                    )
                    repeat(2) {
                        runtime.enable(OWNER)
                        assertEquals(
                            "Enabled",
                            runtime.plugins.value
                                .single()
                                .state,
                            runtime.logs.value.joinToString(),
                        )
                        val sources = withTimeout(5000) { runtime.homeSources.first { it.any { source -> source.owner == OWNER } } }
                        assertEquals(OWNER, sources.single().owner)
                        assertTrue(
                            runtime.homes.pages.value
                                .containsKey(OWNER),
                        )
                        assertTrue(
                            runtime.logins.states.value
                                .containsKey(OWNER),
                        )
                        runtime.logins.request(OWNER)
                        val prompt = withTimeout(5000) { runtime.logins.prompt.first { it?.owner == OWNER } }
                        val browser = assertNotNull(prompt?.web)
                        assertTrue(browser.acceptsNavigation(browser.url))
                        assertEquals("https://music.youtube.com", browser.cookieUrl)
                        assertEquals(
                            "cookie",
                            prompt
                                ?.request
                                ?.fields
                                ?.single()
                                ?.id,
                        )
                        assertTrue(
                            prompt
                                ?.request
                                ?.fields
                                ?.single()
                                ?.secret == true,
                        )
                        runtime.logins.dismiss(OWNER)
                        runtime.disable(OWNER)
                        assertFalse(
                            runtime.logins.states.value
                                .containsKey(OWNER),
                        )
                        assertFalse(
                            runtime.homes.pages.value
                                .containsKey(OWNER),
                        )
                        assertTrue(runtime.registry.audioSources().none { it.owner == OWNER })
                        assertTrue(runtime.registry.searchProviders().none { it.owner == OWNER })
                    }
                } finally {
                    runtime.close()
                    scope.cancel()
                    data.deleteRecursively()
                }
            }
        }

    companion object {
        private const val OWNER = "metrofuse.youtube-music"
    }
}
