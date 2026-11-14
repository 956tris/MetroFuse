package dev.metrofuse.runtime

import dev.metrofuse.api.HttpRequest
import dev.metrofuse.api.HttpResponse
import dev.metrofuse.api.PluginNetwork
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** A separate client has no host credentials, cookie jar or authentication interceptor. */
class HostPluginNetwork : PluginNetwork, AutoCloseable {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    override suspend fun request(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        require(request.url.startsWith("https://") || request.url.startsWith("http://")) { "HTTP URL required" }
        require(request.method in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")) { "Unsupported HTTP method" }
        require(request.maxBytes in 1..2 * 1024 * 1024 && request.timeoutMs in 1..30000)
        val requestBody = request.body
        require(requestBody == null || requestBody.toByteArray().size <= 2 * 1024 * 1024)
        val body = requestBody?.toRequestBody("application/json; charset=utf-8".toMediaType())
            ?: if (request.method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null
        val builder = Request.Builder().url(request.url).method(request.method, body)
        request.headers.forEach { (key, value) -> builder.header(key, value) }
        val call = client.newCall(builder.build())
        call.timeout().timeout(request.timeoutMs, TimeUnit.MILLISECONDS)
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            val input = it.body.byteStream()
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size() + count <= request.maxBytes) { "HTTP response exceeds limit" }
                                output.write(buffer, 0, count)
                            }
                            HttpResponse(it.code, it.headers.names().associateWith { name -> it.header(name).orEmpty() }, output.toString(Charsets.UTF_8.name()))
                        }
                        if (continuation.isActive) continuation.resume(value)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }
    }
}
