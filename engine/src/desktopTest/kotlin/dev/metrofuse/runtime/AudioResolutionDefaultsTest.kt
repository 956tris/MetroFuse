package dev.metrofuse.runtime

import dev.metrofuse.api.AudioCapabilities
import dev.metrofuse.api.AudioPolicy
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.AudioResult
import dev.metrofuse.api.AudioSource
import dev.metrofuse.api.StreamDescriptor
import dev.metrofuse.api.StreamTransport
import dev.metrofuse.api.TrackIdentity
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AudioResolutionDefaultsTest {
    @Test
    fun networkResolutionCanExceedOneSecondAndExplicitTimeoutStillWins() =
        runTest {
            val registry = Registry()
            registry.audioSource(
                "fixture",
                object : AudioSource {
                    override val id = "fixture.audio"
                    override val capabilities = AudioCapabilities(setOf(StreamTransport.DIRECT))

                    override suspend fun resolve(request: AudioQuery): AudioResult {
                        delay(1500)
                        return AudioResult(stream = StreamDescriptor(StreamTransport.DIRECT, "https://audio.example.com/song.mp4"))
                    }
                },
            )
            val pipelines = ProviderPipelines(registry)
            val query = AudioQuery(TrackIdentity("song"), "desktop")
            assertEquals(
                "fixture.audio",
                pipelines
                    .audio(query)
                    .stream
                    ?.attributes
                    ?.get("providerId"),
            )
            pipelines.audioPolicy.value = AudioPolicy(timeoutMillis = 500)
            assertNull(pipelines.audio(query).stream)
        }

    @Test
    fun onlyTheCompleteLegacyDefaultPolicyIsMigrated() {
        assertEquals(AudioPolicy(), migrateAudioPolicy(AudioPolicy(timeoutMillis = 1000)))
        val preferred = AudioPolicy(timeoutMillis = 1000, preferredSource = "chosen.audio")
        assertEquals(preferred, migrateAudioPolicy(preferred))
        val custom = AudioPolicy(timeoutMillis = 5000)
        assertEquals(custom, migrateAudioPolicy(custom))
    }
}
