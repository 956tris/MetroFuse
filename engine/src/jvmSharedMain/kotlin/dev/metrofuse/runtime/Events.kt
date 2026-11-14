package dev.metrofuse.runtime

import dev.metrofuse.api.Subscription
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicInteger

data class EngineEvent(val name: String, val payload: JsonObject)
/** Background publishers suspend when the bounded queue is full. Never call from an audio callback. */
class EngineEvents {
    private val mutable = MutableSharedFlow<EngineEvent>(extraBufferCapacity=64,onBufferOverflow=BufferOverflow.SUSPEND)
    val subscriberCount = AtomicInteger()
    suspend fun publish(name: String, payload: JsonObject) { require(name.length in 1..120 && payload.toString().length <= 64*1024); mutable.emit(EngineEvent(name,payload)) }
    fun subscribe(scope: CoroutineScope, name: String? = null, handler: suspend (EngineEvent) -> Unit): Subscription {
        val job=scope.launch(start=CoroutineStart.UNDISPATCHED) {
            subscriberCount.incrementAndGet()
            try { mutable.filter { name == null || it.name == name }.collect { handler(it) } }
            finally { subscriberCount.decrementAndGet() }
        }
        return Subscription { job.cancel() }
    }
}
