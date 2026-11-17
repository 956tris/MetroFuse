package dev.metrofuse.runtime

import dev.metrofuse.api.PlaybackState
import dev.metrofuse.api.Track
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackEventLimitTest {
    @Test
    fun largeQueueKeepsPlaybackStateAndObserverAlive() =
        runBlocking {
            val root = Files.createTempDirectory("metrofuse-event-limit-").toFile()
            val failures = ConcurrentLinkedQueue<Throwable>()
            val scope =
                CoroutineScope(
                    SupervisorJob() + Dispatchers.Default +
                        CoroutineExceptionHandler {
                            _,
                            error,
                            ->
                            failures.add(error)
                        },
                )
            val host = EngineTestHost()
            val runtime = Runtime(root, "desktop", scope, host)
            val received = Channel<EngineEvent>(Channel.UNLIMITED)
            val subscription = runtime.events.subscribe(scope, "queue_changed") { received.send(it) }
            try {
                val state = host.playback.state as MutableStateFlow<PlaybackState>
                val queue = List(1000) { index -> Track("fixture:$index", "Title ".repeat(30), "Artist", "Album", 180000) }
                state.value = PlaybackState(host.track, playing = true, queue = queue)
                val invalidated =
                    withTimeout(5000) {
                        var event = received.receive()
                        while (event.payload["invalidated"] == null) event = received.receive()
                        event
                    }
                assertEquals(
                    "true",
                    invalidated.payload
                        .getValue("invalidated")
                        .jsonPrimitive.content,
                )
                assertEquals(
                    "1000",
                    invalidated.payload
                        .getValue("totalCount")
                        .jsonPrimitive.content,
                )
                assertFalse("queue" in invalidated.payload)
                assertEquals(queue, state.value.queue)
                assertTrue(state.value.playing)
                state.value = PlaybackState(host.track, playing = true, queue = listOf(host.track))
                val next = withTimeout(5000) { received.receive() }
                assertEquals(1, (next.payload.getValue("queue") as JsonArray).size)
                assertFalse("invalidated" in next.payload)
                assertTrue(failures.isEmpty(), failures.toString())
            } finally {
                subscription.cancel()
                runtime.close()
                scope.cancel()
                root.deleteRecursively()
            }
        }

    @Test
    fun oversizedPlaybackIdentityUsesInvalidationAndLaterEventsStillArrive() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val events = EngineEvents()
            val received = Channel<EngineEvent>(Channel.UNLIMITED)
            val subscription = events.subscribe(scope) { received.send(it) }
            try {
                events.publishHost("playback", buildJsonObject { put("track", "x".repeat(64 * 1024)) })
                assertEquals(
                    "true",
                    withTimeout(5000) { received.receive() }
                        .payload
                        .getValue("invalidated")
                        .jsonPrimitive.content,
                )
                val normal = buildJsonObject { put("playing", false) }
                events.publishHost("playback", normal)
                assertEquals(normal, withTimeout(5000) { received.receive() }.payload)
            } finally {
                subscription.cancel()
                scope.cancel()
            }
        }

    @Test
    fun pluginPublishersKeepPayloadAndNameValidation() =
        runBlocking<Unit> {
            val events = EngineEvents()
            assertFailsWith<IllegalArgumentException> {
                events.publish("queue_changed", buildJsonObject { put("queue", "x".repeat(64 * 1024)) })
            }
            assertFailsWith<IllegalArgumentException> { events.publish("", buildJsonObject {}) }
        }
}
