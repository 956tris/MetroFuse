package dev.metrofuse.runtime

import dev.metrofuse.api.AudioCapabilities
import dev.metrofuse.api.AudioPolicy
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.AudioResult
import dev.metrofuse.api.AudioSource
import dev.metrofuse.api.StreamDescriptor
import dev.metrofuse.api.StreamTransport
import dev.metrofuse.api.TrackIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ProviderCacheInvalidationTest {
    @Test
    fun invalidatedInFlightAudioCannotRepopulateTheCurrentAccountCache() =
        runTest {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val registry = Registry()
            registry.audioSource(
                "fixture",
                object : AudioSource {
                    override val id = "fixture.audio"
                    override val capabilities = AudioCapabilities(setOf(StreamTransport.DIRECT))

                    override suspend fun resolve(request: AudioQuery): AudioResult {
                        calls++
                        val account = if (calls == 1) "previous" else "current"
                        if (calls == 1) {
                            entered.complete(Unit)
                            release.await()
                        }
                        return AudioResult(
                            stream =
                                StreamDescriptor(
                                    StreamTransport.DIRECT,
                                    "https://fixture.test/audio",
                                    expiresAtMillis = 100000,
                                    headers = mapOf("X-Account" to account),
                                ),
                        )
                    }
                },
            )
            val pipelines = ProviderPipelines(registry, now = { 0L })
            pipelines.audioPolicy.value = AudioPolicy(timeoutMillis = 5000, cacheTtlMillis = 60000)
            val query = AudioQuery(TrackIdentity("fixture"), "desktop")
            val pending = async { pipelines.audio(query) }
            entered.await()
            pipelines.invalidateCaches()
            release.complete(Unit)
            assertEquals(
                "previous",
                pending
                    .await()
                    .stream
                    ?.headers
                    ?.get("X-Account"),
            )
            assertEquals(
                "current",
                pipelines
                    .audio(query)
                    .stream
                    ?.headers
                    ?.get("X-Account"),
            )
            assertEquals(
                "current",
                pipelines
                    .audio(query)
                    .stream
                    ?.headers
                    ?.get("X-Account"),
            )
            assertEquals(2, calls)
        }
}
