package dev.metrofuse.runtime

import com.sun.net.httpserver.HttpServer
import dev.metrofuse.api.HttpRequest
import dev.metrofuse.api.HttpResponse
import dev.metrofuse.api.Manifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginNetworkBridgeTest {
    @Test
    fun newHttpFieldsRetainPublishedNativeConstructorsAndCopyDescriptors() {
        val requestParameters =
            arrayOf(
                String::class.java,
                String::class.java,
                Map::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
            )
        assertEquals(HttpRequest::class.java, HttpRequest::class.java.getConstructor(*requestParameters).declaringClass)
        assertEquals(HttpRequest::class.java, HttpRequest::class.java.getMethod("copy", *requestParameters).returnType)
        assertEquals(
            HttpResponse::class.java,
            HttpResponse::class.java.getConstructor(Int::class.javaPrimitiveType, Map::class.java, String::class.java).declaringClass,
        )
    }

    @Test
    fun binaryHttpPreservesProtobufBytesAndEnforcesDecodedSizeLimits() =
        runBlocking<Unit> {
            val bytes = byteArrayOf(0, -1, -128, 10, 13, 42)
            val received = CompletableFuture<ByteArray>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/binary") { exchange ->
                exchange.use {
                    received.complete(it.requestBody.readBytes())
                    it.responseHeaders.add("Set-Cookie", "first=one; Path=/; HttpOnly")
                    it.responseHeaders.add("Set-Cookie", "second=two; Path=/; Secure")
                    it.sendResponseHeaders(200, bytes.size.toLong())
                    it.responseBody.write(bytes)
                }
            }
            server.start()
            val network = HostPluginNetwork()
            try {
                val url = "http://127.0.0.1:${server.address.port}/binary"
                val encoded = Base64.getEncoder().encodeToString(bytes)
                val request =
                    HttpRequest(url, method = "POST", body = encoded).apply {
                        bodyEncoding = "base64"
                        responseType = "base64"
                    }
                val response = network.request(request)
                assertEquals("base64", response.bodyEncoding)
                assertEquals(url, response.finalUrl)
                assertEquals(listOf("first=one; Path=/; HttpOnly", "second=two; Path=/; Secure"), response.setCookies)
                assertContentEquals(bytes, received.get(2, TimeUnit.SECONDS))
                assertContentEquals(bytes, Base64.getDecoder().decode(response.body))
                assertFailsWith<IllegalArgumentException> {
                    network.request(
                        HttpRequest(url, maxBytes = bytes.size - 1).apply {
                            responseType =
                                "base64"
                        },
                    )
                }
                assertFailsWith<IllegalArgumentException> { network.request(HttpRequest(url).apply { responseType = "invalid" }) }
                fixture { runtime ->
                    val script =
                        """
                        globalThis.plugin={async init(host){
                          const response=await host.http({url:'$url',method:'POST',body:'$encoded',bodyEncoding:'base64',responseType:'base64'});
                          host.service('fixture.binary/v1',()=>response);
                        }};
                        """.trimIndent()
                    install(runtime, "test.binary", script)
                    assertEquals(JsonPrimitive(encoded), runtime.registry.invoke("fixture.binary/v1", buildJsonObject {}).getValue("body"))
                    assertEquals(
                        JsonPrimitive("base64"),
                        runtime.registry.invoke("fixture.binary/v1", buildJsonObject {}).getValue("bodyEncoding"),
                    )
                }
            } finally {
                network.close()
                server.stop(0)
            }
        }

    @Test
    fun socketsBufferHandshakeMessagesUntilSubscriptionAndCloseWithTheirOwner() =
        runBlocking<Unit> {
            SocketFixture("first").use { server ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val delivered = Channel<JsonObject>(16)
                val sockets = PluginSockets(scope) { _, event -> delivered.send(event) }
                try {
                    val id = sockets.open(PluginSocketRequest(server.url))
                    sockets.startDelivery()
                    assertNull(withTimeoutOrNull(100) { delivered.receive() })
                    sockets.subscribe(id, true)
                    assertEquals(JsonPrimitive("first"), withTimeout(3000) { delivered.receive() }.getValue("text"))
                    sockets.send(id, "ordered queue with duplicate occurrences")
                    assertEquals(
                        JsonPrimitive("ordered queue with duplicate occurrences"),
                        withTimeout(3000) { delivered.receive() }.getValue("text"),
                    )
                    scope.cancel()
                    assertTrue(server.disconnected.get(3, TimeUnit.SECONDS))
                    assertFailsWith<IllegalArgumentException> { sockets.send(id, "late send") }
                } finally {
                    sockets.close()
                    scope.cancel()
                }
            }
        }

    @Test
    fun oversizedSocketFramesAndUndrainedQueuesCloseWithSafeLimitErrors() =
        runBlocking<Unit> {
            for (frames in listOf(listOf("x".repeat(512 * 1024 + 1)), List(20) { "frame-$it" })) {
                SocketFixture(frames.first(), frames).use { server ->
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    val sockets = PluginSockets(scope) { _, _ -> error("No listener registered") }
                    try {
                        val id = sockets.open(PluginSocketRequest(server.url))
                        withTimeout(5000) { while (!server.disconnected.isDone) delay(10) }
                        val event = sockets.receive(id, 1000)
                        assertEquals(JsonPrimitive("SOCKET_LIMIT"), event?.get("code"))
                    } finally {
                        sockets.close()
                        scope.cancel()
                    }
                }
            }
        }

    @Test
    fun javascriptCanAwaitDealerFramesWithinInitializationAndServiceCallbacks() =
        runBlocking<Unit> {
            SocketFixture("connection-id").use { server ->
                fixture { runtime ->
                    val script =
                        """
                        globalThis.plugin={async init(host){
                          const id=await host.socket.open({url:'${server.url}'});
                          const first=await host.socket.receive(id,3000);
                          if(first.text!=='connection-id')throw Error('Missing dealer handshake');
                          host.service('fixture.socket-await/v1',async ()=>{
                            await host.socket.send(id,'replace_state');
                            return await host.socket.receive(id,3000);
                          });
                        }};
                        """.trimIndent()
                    install(runtime, "test.socket-await", script, true)
                    assertEquals(
                        JsonPrimitive("replace_state"),
                        runtime.registry.invoke("fixture.socket-await/v1", buildJsonObject {}).getValue("text"),
                    )
                }
                assertTrue(server.disconnected.get(3, TimeUnit.SECONDS))
            }
        }

    @Test
    fun javascriptSocketEventsRemainSessionPrivateAndDisableClosesOnlyItsConnections() =
        runBlocking<Unit> {
            SocketFixture("queue-a").use { first ->
                SocketFixture("queue-b").use { second ->
                    fixture { runtime ->
                        fun script(
                            url: String,
                            service: String,
                        ) = """
                            globalThis.plugin={async init(host){
                              let messages=[];
                              const id=await host.socket.open({url:'$url'});
                              host.on('socket.message/'+id,event=>{if(event.type==='text')messages.push(event.text);});
                              host.service('$service',()=>({messages}));
                            }};
                            """.trimIndent()
                        install(runtime, "test.socket-a", script(first.url, "fixture.socket-a/v1"), true)
                        install(runtime, "test.socket-b", script(second.url, "fixture.socket-b/v1"), true)

                        suspend fun messages(service: String) = runtime.registry.invoke(service, buildJsonObject {}).getValue("messages")
                        withTimeout(5000) {
                            while (messages("fixture.socket-a/v1").toString() == "[]" ||
                                messages("fixture.socket-b/v1").toString() == "[]"
                            ) {
                                delay(20)
                            }
                        }
                        assertEquals("[\"queue-a\"]", messages("fixture.socket-a/v1").toString())
                        assertEquals("[\"queue-b\"]", messages("fixture.socket-b/v1").toString())
                        runtime.disable("test.socket-a")
                        assertTrue(first.disconnected.get(3, TimeUnit.SECONDS))
                        assertFalse(second.disconnected.isDone)
                        assertEquals("[\"queue-b\"]", messages("fixture.socket-b/v1").toString())
                    }
                    assertTrue(second.disconnected.get(3, TimeUnit.SECONDS))
                }
            }
        }

    @Test
    fun websocketPermissionAndValidationRejectUnsafeRequestsWithoutExposingTokens() =
        runBlocking<Unit> {
            val token = "synthetic-access-token"
            for (url in listOf(
                "https://dealer.example.invalid/?token=$token",
                "ws://dealer.example.invalid/?token=$token",
                "wss://user:$token@dealer.example.invalid",
                "wss://dealer.example.invalid/#$token",
            )) {
                val failure = assertFailsWith<IllegalArgumentException> { PluginSockets.validate(PluginSocketRequest(url)) }
                assertFalse(failure.toString().contains(token))
            }
            assertFailsWith<IllegalArgumentException> {
                PluginSockets.validate(
                    PluginSocketRequest(
                        "wss://dealer.example.invalid",
                        mapOf("Sec-WebSocket-Key" to "spoof"),
                    ),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                PluginSockets.validate(
                    PluginSocketRequest(
                        "wss://dealer.example.invalid",
                        mapOf("Authorization" to "a\r\nInjected: b"),
                    ),
                )
            }
            fixture { runtime ->
                val script =
                    """
                    globalThis.plugin={async init(host){
                      await host.socket.open({url:'wss://dealer.example.invalid/?token=$token'});
                    }};
                    """.trimIndent()
                val manifest =
                    Manifest("test.no-socket", "Socket permission fixture", "1.0.0", "Tests", runtime = "javascript", script = "index.js")
                runtime.install(ByteArrayInputStream(packageBytes(manifest, script)))
                runtime.enable(manifest.id)
                assertEquals(
                    "Failed",
                    runtime.plugins.value
                        .single()
                        .state,
                )
                assertTrue(
                    runtime.logs.value
                        .joinToString()
                        .contains("WebSocket capability required"),
                )
                assertFalse(
                    runtime.logs.value
                        .joinToString()
                        .contains(token),
                )
            }
        }

    private suspend fun install(
        runtime: Runtime,
        id: String,
        script: String,
        socket: Boolean = false,
    ) {
        val manifest =
            Manifest(
                id,
                "Network fixture",
                "1.0.0",
                "Tests",
                runtime = "javascript",
                script = "index.js",
                capabilities = if (socket) listOf("network.websocket") else emptyList(),
            )
        runtime.install(ByteArrayInputStream(packageBytes(manifest, script)))
        runtime.enable(id)
        assertEquals(
            "Enabled",
            runtime.plugins.value
                .first { it.manifest.id == id }
                .state,
            runtime.logs.value.joinToString(),
        )
    }

    private fun packageBytes(
        manifest: Manifest,
        script: String,
    ) = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            for ((name, contents) in mapOf("manifest.json" to codec.encodeToString(manifest), "index.js" to script)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents.toByteArray())
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    private suspend fun fixture(block: suspend (Runtime) -> Unit) {
        val data = Files.createTempDirectory("metrofuse-network-bridge-").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = Runtime(data, "desktop", scope, EngineTestHost())
        try {
            runtime.initialize()
            block(runtime)
        } finally {
            runtime.close()
            scope.cancel()
            data.deleteRecursively()
        }
    }

    /** A real RFC 6455 server sends the first dealer-style frame with the handshake. */
    private class SocketFixture(
        private val first: String,
        private val initialFrames: List<String> = listOf(first),
    ) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val executor =
            Executors.newSingleThreadExecutor { task ->
                Thread(task, "plugin-websocket-fixture").apply { isDaemon = true }
            }
        val url = "ws://127.0.0.1:${server.localPort}/dealer?token=synthetic-token"
        val disconnected = CompletableFuture<Boolean>()

        init {
            executor.submit {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 10000
                        val input = DataInputStream(socket.getInputStream())
                        val output = socket.getOutputStream()
                        val headers = generateSequence { input.readLine()?.takeIf(String::isNotEmpty) }.toList()
                        val key = headers.first { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
                        val accept =
                            Base64.getEncoder().encodeToString(
                                MessageDigest.getInstance("SHA-1").digest(
                                    (
                                        key +
                                            "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
                                    ).toByteArray(),
                                ),
                            )
                        output.write(
                            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
                                .toByteArray(),
                        )

                        fun frame(text: String) {
                            val payload = text.toByteArray()
                            output.write(0x81)
                            if (payload.size < 126) {
                                output.write(payload.size)
                            } else if (payload.size <= 65535) {
                                output.write(126)
                                output.write(payload.size ushr 8)
                                output.write(payload.size and 255)
                            } else {
                                output.write(127)
                                for (shift in 56 downTo 0 step 8) output.write((payload.size.toLong() ushr shift).toInt() and 255)
                            }
                            output.write(payload)
                            output.flush()
                        }
                        initialFrames.forEach(::frame)
                        while (true) {
                            val type = input.read()
                            if (type < 0) break
                            val size = input.readUnsignedByte()
                            require(size and 0x80 != 0)
                            val length = size and 0x7f
                            require(length < 126)
                            val mask = ByteArray(4).also(input::readFully)
                            val payload = ByteArray(length).also(input::readFully)
                            for (index in payload.indices) payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
                            if (type and 0x0f == 8) break
                            frame(payload.toString(Charsets.UTF_8))
                        }
                    }
                    disconnected.complete(true)
                } catch (failure: Exception) {
                    disconnected.completeExceptionally(failure)
                }
            }
        }

        override fun close() {
            server.close()
            executor.shutdownNow()
        }
    }
}
