package com.metrolist.android.plugins

import androidx.test.platform.app.InstrumentationRegistry
import dev.metrofuse.api.HostLogger
import dev.metrofuse.api.HostNavigation
import dev.metrofuse.api.HostServices
import dev.metrofuse.api.HostSettings
import dev.metrofuse.api.PlaybackState
import dev.metrofuse.api.Player
import dev.metrofuse.api.Track
import dev.metrofuse.api.TrackCatalog
import dev.metrofuse.api.TrackIdentity
import dev.metrofuse.runtime.Runtime
import dev.metrofuse.runtime.initializeNativePlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class YouTubeMusicPackageTest {
    @Test
    fun releasedApkImportsActivatesAndContributesItsSdkAccountScreen(): Unit =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            assumeTrue(
                instrumentation.context.assets
                    .list("")
                    .orEmpty()
                    .contains("youtube-music.apk"),
            )
            val application = instrumentation.targetContext.applicationContext
            initializeNativePlatform(application)
            val directory = File(application.cacheDir, "ytm-package-${UUID.randomUUID()}")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runtime = Runtime(directory, "android", scope, PackageTestHost())
            try {
                runtime.initialize()
                instrumentation.context.assets
                    .open("youtube-music.apk")
                    .use { runtime.installOrUpdate(it, "youtube-music.apk") }
                runtime.enable("metrofuse.youtube-music")
                val status = runtime.plugins.value.single()
                assertEquals(status.error ?: runtime.logs.value.joinToString(), "Enabled", status.state)
                assertEquals("dev.metrofuse.youtube.YouTubeMusicPlugin", status.manifest.entryClass)
                runtime.logins.request("metrofuse.youtube-music")
                withTimeout(10_000) { runtime.logins.prompt.first { it?.owner == "metrofuse.youtube-music" } }
                withTimeout(10_000) { runtime.logins.states.first { it["metrofuse.youtube-music"]?.ui != null } }
                assertNotNull(
                    runtime.logins.states.value["metrofuse.youtube-music"]
                        ?.ui,
                )
            } finally {
                runtime.close()
                scope.cancel()
                directory.deleteRecursively()
            }
        }
}

private class PackageTestHost : HostServices {
    override val playback =
        object : Player {
            override val state = MutableStateFlow(PlaybackState(Track("", "", "", "")))

            override fun toggle() = Unit

            override fun seek(position: Long) = Unit

            override fun next() = Unit
        }
    override val navigation =
        object : HostNavigation {
            override val destination = MutableStateFlow("home")

            override fun navigate(destination: String) {
                this.destination.value = destination
            }
        }
    override val catalog =
        object : TrackCatalog {
            override val tracks = emptyList<Track>()

            override suspend fun identify(identity: TrackIdentity): Track? = null
        }
    override val settings =
        object : HostSettings {
            override fun get(key: String): String? = null

            override fun set(
                key: String,
                value: String?,
            ) = Unit
        }
    override val logger = HostLogger { _, _ -> }
}
