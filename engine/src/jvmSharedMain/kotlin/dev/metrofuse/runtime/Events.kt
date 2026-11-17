package dev.metrofuse.runtime

import dev.metrofuse.api.Subscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

data class EngineEvent(
    val name: String,
    val payload: JsonObject,
)

/** Background publishers suspend when the bounded queue is full. Never call from an audio callback. */
class EngineEvents {
    private val mutable = MutableSharedFlow<EngineEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
    val subscriberCount = AtomicInteger()

    suspend fun publish(
        name: String,
        payload: JsonObject,
    ) {
        require(name.length in 1..120 && payload.toString().length <= 64 * 1024)
        mutable.emit(EngineEvent(name, payload))
    }

    internal suspend fun publishHost(
        name: String,
        payload: JsonObject,
    ) {
        require(name.length in 1..120)
        // A notification must not crash playback when the authoritative state outgrows the event transport.
        val bounded =
            if (payload.toString().length <= 64 * 1024) {
                payload
            } else {
                buildJsonObject {
                    put("invalidated", true)
                    (payload["queue"] as? JsonArray)?.let { put("totalCount", it.size) }
                }
            }
        mutable.emit(EngineEvent(name, bounded))
    }

    fun subscribe(
        scope: CoroutineScope,
        name: String? = null,
        handler: suspend (EngineEvent) -> Unit,
    ): Subscription {
        val job =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                subscriberCount.incrementAndGet()
                try {
                    mutable.filter { name == null || it.name == name }.collect { handler(it) }
                } finally {
                    subscriberCount.decrementAndGet()
                }
            }
        return Subscription { job.cancel() }
    }
}
