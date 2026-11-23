package dev.metrofuse.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

@Serializable
internal data class PluginSocketRequest(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 15000,
)

/** A session owns its sockets and their queued events, including before JS registers a listener. */
internal class PluginSockets(
    private val scope: CoroutineScope,
    private val deliver: suspend (String, JsonObject) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val entries = mutableMapOf<String, Entry>()
    private val client =
        lazy {
            OkHttpClient
                .Builder()
                .pingInterval(25, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
    private var closed = false
    private var started = false

    init {
        scope.coroutineContext[Job]?.invokeOnCompletion { close() }
    }

    private inner class Entry(
        val id: String,
    ) {
        val opened = CompletableDeferred<Unit>()
        var subscribed = false
        val queue = Channel<JsonObject>(16)
        var socket: WebSocket? = null
        var terminal = false
        var receiving = false
        var consumer: Job? = null

        fun offer(
            event: JsonObject,
            final: Boolean = false,
        ) = synchronized(lock) {
            if (closed || entries[id] !== this || terminal) return@synchronized
            if (final) terminal = true
            if (!queue.trySend(event).isSuccess) {
                terminal = true
                while (queue.tryReceive().isSuccess) {
                    // Discard buffered data before reporting the terminal limit error.
                }
                queue.trySend(errorEvent("SOCKET_LIMIT"))
                socket?.cancel()
            }
        }

        fun consume() {
            if (consumer?.isActive == true || !subscribed) return
            consumer =
                scope.launch {
                    for (event in queue) {
                        deliver("socket.message/$id", event)
                        if (event["type"]?.toString() != "\"text\"") {
                            synchronized(lock) { if (entries[id] === this@Entry) entries.remove(id) }
                            queue.close()
                            break
                        }
                    }
                }
        }
    }

    suspend fun open(request: PluginSocketRequest): String {
        validate(request)
        val entry =
            synchronized(lock) {
                check(!closed) { "Plugin sockets are closed" }
                require(entries.size < 4) { "Socket count limit" }
                Entry(UUID.randomUUID().toString()).also { entries[it.id] = it }
            }
        try {
            val builder = Request.Builder().url(request.url)
            request.headers.forEach { (name, value) -> builder.header(name, value) }
            val socket =
                client.value.newWebSocket(
                    builder.build(),
                    object : WebSocketListener() {
                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response,
                        ) {
                            entry.opened.complete(Unit)
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            text: String,
                        ) {
                            if (text.length > 512 * 1024 || text.toByteArray().size > 512 * 1024) {
                                entry.offer(errorEvent("SOCKET_LIMIT"), true)
                                webSocket.cancel()
                            } else {
                                entry.offer(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", text)
                                    },
                                )
                            }
                        }

                        override fun onMessage(
                            webSocket: WebSocket,
                            bytes: ByteString,
                        ) {
                            entry.offer(errorEvent("SOCKET_BINARY_UNSUPPORTED"), true)
                            webSocket.cancel()
                        }

                        override fun onClosing(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            webSocket.close(code, null)
                        }

                        override fun onClosed(
                            webSocket: WebSocket,
                            code: Int,
                            reason: String,
                        ) {
                            entry.offer(
                                buildJsonObject {
                                    put("type", "closed")
                                    put("code", code)
                                },
                                true,
                            )
                        }

                        override fun onFailure(
                            webSocket: WebSocket,
                            t: Throwable,
                            response: Response?,
                        ) {
                            // Transport exceptions and close reasons may contain credentials from the URL.
                            entry.opened.completeExceptionally(IOException("SOCKET_FAILED"))
                            entry.offer(errorEvent("SOCKET_FAILED"), true)
                        }
                    },
                )
            synchronized(lock) {
                entry.socket = socket
                if (closed || entries[entry.id] !== entry || entry.terminal) socket.cancel()
            }
            withTimeout(request.timeoutMs) { entry.opened.await() }
            synchronized(lock) { if (started && entry.subscribed) entry.consume() }
            return entry.id
        } catch (failure: Throwable) {
            close(entry.id)
            if (failure is CancellationException) throw failure
            throw IOException("SOCKET_FAILED")
        }
    }

    fun send(
        id: String,
        text: String,
    ) {
        require(text.length <= 512 * 1024 && text.toByteArray().size <= 512 * 1024) { "Socket message limit" }
        synchronized(lock) {
            val entry = requireNotNull(entries[id]) { "Unknown plugin socket" }
            check(!closed && !entry.terminal && entry.socket?.send(text) == true) { "Socket send failed" }
        }
    }

    fun subscribe(
        id: String,
        active: Boolean,
    ) = synchronized(lock) {
        entries[id]?.let { entry ->
            check(!active || !entry.receiving) { "Socket receive is active" }
            entry.subscribed = active
            if (active && started) {
                entry.consume()
            } else if (!active) {
                entry.consumer?.cancel()
            }
        }
    }

    suspend fun receive(
        id: String,
        timeoutMs: Long,
    ): JsonObject? {
        require(timeoutMs in 0..30000) { "Socket receive timeout limit" }
        val entry =
            synchronized(lock) {
                val found = requireNotNull(entries[id]) { "Unknown plugin socket" }
                check(!found.subscribed && !found.receiving) { "Socket already has a receiver" }
                found.receiving = true
                found
            }
        try {
            val event =
                if (timeoutMs ==
                    0L
                ) {
                    entry.queue.tryReceive().getOrNull()
                } else {
                    withTimeoutOrNull(timeoutMs) { entry.queue.receive() }
                }
            if (event != null && event["type"]?.toString() != "\"text\"") close(id)
            return event
        } finally {
            synchronized(lock) { entry.receiving = false }
        }
    }

    fun startDelivery() =
        synchronized(lock) {
            check(!closed)
            started = true
            entries.values.forEach { it.consume() }
        }

    fun close(id: String) =
        synchronized(lock) {
            entries.remove(id)?.let { entry ->
                entry.socket?.cancel()
                entry.consumer?.cancel()
                entry.opened.completeExceptionally(IOException("SOCKET_CLOSED"))
                entry.queue.cancel()
            }
            Unit
        }

    override fun close() =
        synchronized(lock) {
            closed = true
            entries.keys.toList().forEach(::close)
            if (client.isInitialized()) {
                client.value.dispatcher.cancelAll()
                client.value.connectionPool.evictAll()
                client.value.dispatcher.executorService
                    .shutdown()
            }
        }

    companion object {
        internal fun validate(request: PluginSocketRequest) {
            val uri =
                try {
                    URI(request.url)
                } catch (_: Exception) {
                    throw IllegalArgumentException("Invalid socket URL")
                }
            require(uri.userInfo == null && uri.fragment == null && !uri.host.isNullOrBlank()) { "Invalid socket URL" }
            require(
                uri.scheme == "wss" || (uri.scheme == "ws" && uri.host in setOf("127.0.0.1", "localhost", "[::1]")),
            ) { "Secure socket URL required" }
            require(request.url.length <= 8192 && request.timeoutMs in 1..30000) { "Socket request limit" }
            require(
                request.headers.size <= 32 &&
                    request.headers.all { (name, value) ->
                        name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")) && value.length <= 8192 &&
                            value.all { it == '\t' || it in ' '..'~' } &&
                            name.lowercase() !in setOf("host", "connection", "upgrade", "content-length", "transfer-encoding") &&
                            !name.startsWith("sec-websocket-", ignoreCase = true)
                    },
            ) { "Invalid socket headers" }
        }

        private fun errorEvent(code: String) =
            buildJsonObject {
                put("type", "error")
                put("code", code)
            }
    }
}
