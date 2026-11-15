package dev.metrofuse.runtime

import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.HostServices
import dev.metrofuse.api.SearchRequest
import dev.metrofuse.api.Track
import dev.metrofuse.api.TrackCatalog
import dev.metrofuse.api.TrackIdentity
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MixedProviderAudioTest {
    @Test
    fun standaloneJioPackageMatchesYtmMetadataAndDeliversAudioLive() =
        runBlocking {
            assumeTrue("Live provider checks are opt-in", System.getenv("METROFUSE_MIXED_AUDIO_LIVE") == "1")
            val ytm = File(requireNotNull(System.getProperty("metrofuse.ytm.native")))
            val jio = File(requireNotNull(System.getProperty("metrofuse.jio.package")))
            val host = CatalogHost()
            val data = Files.createTempDirectory("mixed-provider-audio-").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runtime = Runtime(data, "desktop", scope, host)
            val http = HttpClient(CIO)
            try {
                withTimeout(120_000) {
                    runtime.initialize()
                    for (artifact in listOf(ytm, jio)) {
                        artifact.inputStream().use { runtime.install(it, artifact.name) }
                    }
                    runtime.enable("metrofuse.youtube-music")
                    runtime.enable("metrofuse.jiosaavn")
                    assertTrue(runtime.plugins.value.all { it.state == "Enabled" }, runtime.logs.value.joinToString())
                    val provider =
                        runtime.registry
                            .searchProviders()
                            .single { it.owner == "metrofuse.youtube-music" }
                            .provider
                    val results = provider.search(SearchRequest("Don Toliver No Idea", 15))
                    val track =
                        assertNotNull(
                            results.tracks.firstOrNull {
                                it.title.equals("No Idea", true) &&
                                    it.artist.contains("Don Toliver", true)
                            },
                        )
                    host.tracksById[track.id] = track
                    runtime.pipelines.audioPolicy.value =
                        runtime.pipelines.audioPolicy.value
                            .copy(preferredSource = "jiosaavn.audio")
                    val identity = TrackIdentity(track.extras.getValue("youtube"), track.extras + ("host" to track.id))
                    val result = runtime.pipelines.audio(AudioQuery(identity, "desktop"), includeBuiltin = false)
                    val stream =
                        assertNotNull(result.stream, "Mixed audio failed: ${result.error?.code}; ${runtime.pipelines.diagnostics.value}")
                    assertEquals("jiosaavn.audio", stream.attributes["providerId"])
                    assertEquals("full song", stream.attributes["content"])
                    val response =
                        http.get(stream.locator) {
                            stream.headers.forEach { (name, value) -> header(name, value) }
                            header(HttpHeaders.Range, "bytes=0-4095")
                        }
                    assertTrue(response.status.isSuccess())
                    val channel = response.bodyAsChannel()
                    try {
                        val count = channel.readAvailable(ByteArray(4096), 0, 4096)
                        assertTrue(count > 0)
                        println("YTM metadata matched to Jio full-song audio; received $count media bytes using ${jio.extension}.")
                    } finally {
                        channel.cancel(null)
                    }
                }
            } finally {
                http.close()
                runtime.close()
                scope.cancel()
                data.deleteRecursively()
            }
        }
}

private class CatalogHost : HostServices by EngineTestHost() {
    val tracksById = mutableMapOf<String, Track>()
    override val catalog =
        object : TrackCatalog {
            override val tracks: List<Track> get() = tracksById.values.toList()

            override suspend fun identify(identity: TrackIdentity): Track? =
                tracksById[identity.identifiers["host"] ?: identity.canonicalId]
        }
}
