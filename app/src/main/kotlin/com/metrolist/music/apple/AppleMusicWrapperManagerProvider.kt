package com.metrolist.music.apple

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.BufferedSource
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object AppleMusicWrapperManagerProvider {
    private const val TAG = "AppleALAC"
    const val DEFAULT_HOST = "wm.wol.moe"
    private const val DECRYPT_BATCH_SIZE = 96
    private const val DECRYPT_RETRY_BATCH_SIZE = 12
    private const val DECRYPT_MISSING_RETRY_COUNT = 10
    private const val DECRYPT_SINGLE_SAMPLE_DUPLICATES = 6
    private const val DECRYPT_FINAL_SINGLE_ROUNDS = 6
    private const val DECRYPT_MISSING_RETRY_DELAY_MS = 180L
    private const val MIN_UNDECRYPTED_COMPARE_BYTES = 32
    private const val WRAPPER_CONNECT_TIMEOUT_SECONDS = 10L
    private const val WRAPPER_READ_TIMEOUT_SECONDS = 25L
    private const val WRAPPER_CALL_TIMEOUT_SECONDS = 30L
    private const val WRAPPER_UNARY_CONNECT_TIMEOUT_SECONDS = 3L
    private const val WRAPPER_UNARY_READ_TIMEOUT_SECONDS = 6L
    private const val WRAPPER_UNARY_CALL_TIMEOUT_SECONDS = 7L
    private const val WRAPPER_STATUS_CACHE_MS = 20_000L
    private const val DECRYPT_HEDGE_DELAY_MS = 1_800L
    private const val STREAMING_FIRST_REPLY_TIMEOUT_MS = 1_200L
    private const val STREAMING_SAMPLE_TIMEOUT_MS = 8_000L
    private const val STREAMING_WRITE_POLL_MS = 250L

    enum class WrapperMode(
        val idSuffix: String,
        val title: String,
        val quality: Int,
        val hlsCodecs: String,
        val sampleEntryType: String,
        val keySuffix: String,
        val m3u8RpcPath: String,
        val decryptRpcPath: String,
        val requestKind: RequestKind,
    ) {
        ALAC(
            idSuffix = "alac",
            title = "Apple Music ALAC (Wrapper)",
            quality = 11,
            hlsCodecs = "alac",
            sampleEntryType = "alac",
            keySuffix = "c23",
            m3u8RpcPath = "/manager.v1.WrapperManagerService/M3U8",
            decryptRpcPath = "/manager.v1.WrapperManagerService/Decrypt",
            requestKind = RequestKind.M3U8
        ),
        AAC(
            idSuffix = "aac",
            title = "Apple Music AAC (Wrapper)",
            quality = 8,
            hlsCodecs = "mp4a.40.2",
            sampleEntryType = "mp4a",
            keySuffix = "c22",
            m3u8RpcPath = "/manager.v1.WrapperManagerService/M3U8",
            decryptRpcPath = "/manager.v1.WrapperManagerService/Decrypt",
            requestKind = RequestKind.M3U8
        ),
        DOLBY_ATMOS(
            idSuffix = "atmos",
            title = "Apple Music Dolby Atmos (Wrapper)",
            quality = 10,
            hlsCodecs = "ec-3",
            sampleEntryType = "ec-3",
            keySuffix = "c24",
            m3u8RpcPath = "/manager.v1.WrapperManagerService/M3U8",
            decryptRpcPath = "/manager.v1.WrapperManagerService/Decrypt",
            requestKind = RequestKind.M3U8
        )
    }

    enum class RequestKind {
        M3U8,
        WEB_PLAYBACK
    }

    class WrapperManagerException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class WrapperM3u8(
        val host: String,
        val secure: Boolean,
        val url: String,
    )

    data class WrapperInstance(
        val host: String,
        val secure: Boolean,
    )

    data class DecryptSample(
        val sampleIndex: Int,
        val data: ByteArray,
    )

    interface SampleDecryptClient : AutoCloseable {
        fun decryptSegment(
            adamId: String,
            key: String,
            samples: List<DecryptSample>,
            context: String? = null,
        ): Map<Int, ByteArray>

        fun reportSegmentIntegrityFailure(context: String, reason: String): Boolean = false

        fun clearSegmentContext(context: String) = Unit

        override fun close() = Unit
    }

    private data class DecryptReplySample(
        val adamId: String,
        val key: String,
        val sampleIndex: Int,
        val data: ByteArray,
    )

    private data class PendingDecryptKey(
        val adamId: String,
        val key: String,
        val sampleIndex: Int,
    )

    private val grpcMediaType = "application/grpc".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(WRAPPER_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(WRAPPER_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(WRAPPER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val h2cGrpcClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
        .connectTimeout(WRAPPER_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(WRAPPER_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(WRAPPER_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val defaultGrpcClients = GrpcClients(client, h2cGrpcClient)
    private val unaryGrpcClient = client.newBuilder()
        .connectTimeout(WRAPPER_UNARY_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(WRAPPER_UNARY_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(WRAPPER_UNARY_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val unaryH2cGrpcClient = h2cGrpcClient.newBuilder()
        .connectTimeout(WRAPPER_UNARY_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(WRAPPER_UNARY_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(WRAPPER_UNARY_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val unaryGrpcClients = GrpcClients(unaryGrpcClient, unaryH2cGrpcClient)
    private val decryptRaceExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "AppleWrapperDecryptRace").apply { isDaemon = true }
    }
    private val statusCache = ConcurrentHashMap<String, CachedStatus>()

    private data class CachedStatus(
        val status: WrapperStatus,
        val expiresAtMs: Long,
    )

    private data class GrpcClients(
        val https: OkHttpClient,
        val h2c: OkHttpClient,
    ) {
        fun cancelAll() {
            https.dispatcher.cancelAll()
            h2c.dispatcher.cancelAll()
        }
    }

    private data class DecryptCandidateResult(
        val host: String,
        val samples: Map<Int, ByteArray>?,
        val error: Throwable?,
    )

    private data class DecryptResult(
        val host: String,
        val samples: Map<Int, ByteArray>,
    )

    fun normalizeHost(raw: String?): String {
        return raw.orEmpty()
            .trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trim('/')
            .takeIf { it.isNotBlank() }
            ?: DEFAULT_HOST
    }

    fun buildUrl(host: String, secure: Boolean, path: String): String {
        val normalized = normalizeHost(host)
        val scheme = if (secure) "https" else "http"
        return "$scheme://$normalized$path"
    }

    fun defaultInstances(): List<WrapperInstance> =
        DEFAULT_INSTANCES.map { (host, secure) -> WrapperInstance(host, secure) }

    fun getM3u8(
        adamId: String,
        host: String,
        secure: Boolean,
        mode: WrapperMode,
    ): String {
        val payload = when (mode.requestKind) {
            RequestKind.M3U8 -> encodeM3u8Request(adamId)
            RequestKind.WEB_PLAYBACK -> encodeWebPlaybackRequest(adamId)
        }
        val responseBytes = callUnary(
            url = buildUrl(host, secure, mode.m3u8RpcPath),
            payload = payload,
            grpcClients = unaryGrpcClients,
        )
        val reply = parseM3u8LikeReply(responseBytes)
        if (reply.code != 0) {
            throw WrapperManagerException(
                "wrapper-manager ${mode.title} failed: ${reply.message.ifBlank { "code ${reply.code}" }}"
            )
        }
        return reply.m3u8.takeIf { it.isNotBlank() }
            ?: throw WrapperManagerException("wrapper-manager ${mode.title} returned an empty M3U8 URL")
    }

    fun getM3u8WithFallback(
        adamId: String,
        preferredHost: String? = null,
        preferredSecure: Boolean = true,
        mode: WrapperMode,
    ): WrapperM3u8 {
        val candidates = buildList {
            val normalizedPreferred = normalizeHost(preferredHost)
            add(normalizedPreferred to preferredSecure)
            DEFAULT_INSTANCES.forEach { instance ->
                val normalized = normalizeHost(instance.first)
                if (none { it.first == normalized }) {
                    add(normalized to instance.second)
                }
            }
        }

        val errors = mutableListOf<String>()
        candidates.forEach { (candidateHost, candidateSecure) ->
            runCatching {
                getM3u8(
                    adamId = adamId,
                    host = candidateHost,
                    secure = candidateSecure,
                    mode = mode,
                )
            }.onSuccess { m3u8 ->
                return WrapperM3u8(
                    host = candidateHost,
                    secure = candidateSecure,
                    url = m3u8,
                )
            }.onFailure { error ->
                errors += "$candidateHost: ${error.message ?: error.javaClass.simpleName}"
            }
        }

        throw WrapperManagerException(
            "wrapper-manager ${mode.title} failed on every instance: ${errors.joinToString("; ")}"
        )
    }

    fun openSampleDecryptClient(
        host: String,
        secure: Boolean,
        mode: WrapperMode,
    ): SampleDecryptClient {
        return StreamingThenBatchSampleDecryptClient(host = host, secure = secure, mode = mode)
    }

    fun requireDirectPlayableHls(m3u8Url: String, mode: WrapperMode): String {
        if (mode == WrapperMode.ALAC) {
            return m3u8Url
        }
        val rootPlaylist = downloadPlaylist(m3u8Url)
        if (rootPlaylist.hasProtectedAppleKeys()) {
            throw encryptedHlsException(mode)
        }

        val childPlaylistUrl = rootPlaylist.firstChildPlaylistUrl(m3u8Url)
        if (childPlaylistUrl != null) {
            val childPlaylist = downloadPlaylist(childPlaylistUrl)
            if (childPlaylist.hasProtectedAppleKeys()) {
                throw encryptedHlsException(mode)
            }
        }

        return m3u8Url
    }

    fun status(host: String, secure: Boolean): WrapperStatus {
        val responseBytes = callUnary(
            url = buildUrl(host, secure, "/manager.v1.WrapperManagerService/Status"),
            payload = ByteArray(0),
            grpcClients = unaryGrpcClients,
        )
        return parseStatusReply(responseBytes)
    }

    private fun cachedStatus(host: String, secure: Boolean): WrapperStatus {
        val normalized = normalizeHost(host)
        val cacheKey = "${if (secure) "https" else "http"}://$normalized"
        val now = System.currentTimeMillis()
        statusCache[cacheKey]
            ?.takeIf { it.expiresAtMs > now }
            ?.let { return it.status }
        return status(normalized, secure).also { status ->
            statusCache[cacheKey] = CachedStatus(
                status = status,
                expiresAtMs = now + WRAPPER_STATUS_CACHE_MS,
            )
        }
    }

    fun decryptSegment(
        host: String,
        secure: Boolean,
        mode: WrapperMode,
        adamId: String,
        key: String,
        samples: List<DecryptSample>,
    ): Map<Int, ByteArray> {
        return decryptSegmentWithClients(
            host = host,
            secure = secure,
            mode = mode,
            adamId = adamId,
            key = key,
            samples = samples,
            grpcClients = defaultGrpcClients,
        ).samples
    }

    private fun decryptSegmentWithClients(
        host: String,
        secure: Boolean,
        mode: WrapperMode,
        adamId: String,
        key: String,
        samples: List<DecryptSample>,
        grpcClients: GrpcClients,
        deprioritizedHosts: Set<String> = emptySet(),
    ): DecryptResult {
        val candidates = buildInstanceCandidates(host, secure, deprioritizedHosts)
        if (candidates.size > 1) {
            return decryptSegmentWithHedgedInstances(
                candidates = candidates,
                mode = mode,
                adamId = adamId,
                key = key,
                samples = samples,
                grpcClients = grpcClients,
            )
        }

        val errors = mutableListOf<String>()
        candidates.forEach { (candidateHost, candidateSecure) ->
            runCatching {
                decryptSegmentOnInstance(
                    host = candidateHost,
                    secure = candidateSecure,
                    mode = mode,
                    adamId = adamId,
                    key = key,
                    samples = samples,
                    grpcClients = grpcClients,
                )
            }.onSuccess { return DecryptResult(candidateHost, it) }
                .onFailure { error ->
                    errors += "$candidateHost: ${error.message ?: error.javaClass.simpleName}"
                }
        }

        throw WrapperManagerException(
            "wrapper-manager Decrypt failed on every instance: ${errors.joinToString("; ")}"
        )
    }

    private fun decryptSegmentWithHedgedInstances(
        candidates: List<Pair<String, Boolean>>,
        mode: WrapperMode,
        adamId: String,
        key: String,
        samples: List<DecryptSample>,
        grpcClients: GrpcClients,
    ): DecryptResult {
        val active = mutableListOf<CompletableFuture<DecryptCandidateResult>>()
        val errors = mutableListOf<String>()
        var nextCandidateIndex = 0

        fun submitNextCandidate() {
            val (candidateHost, candidateSecure) = candidates[nextCandidateIndex++]
            active += CompletableFuture.supplyAsync(
                {
                    try {
                        DecryptCandidateResult(
                            host = candidateHost,
                            samples = decryptSegmentOnInstance(
                                host = candidateHost,
                                secure = candidateSecure,
                                mode = mode,
                                adamId = adamId,
                                key = key,
                                samples = samples,
                                grpcClients = grpcClients,
                            ),
                            error = null,
                        )
                    } catch (error: Exception) {
                        Timber.tag(TAG).w(error, "ALAC decrypt candidate failed on $candidateHost")
                        DecryptCandidateResult(
                            host = candidateHost,
                            samples = null,
                            error = error,
                        )
                    }
                },
                decryptRaceExecutor,
            )
        }

        submitNextCandidate()
        while (active.isNotEmpty() || nextCandidateIndex < candidates.size) {
            val completedFuture = awaitAnyDecryptCandidate(
                active = active,
                timeoutMs = if (nextCandidateIndex < candidates.size) {
                    DECRYPT_HEDGE_DELAY_MS
                } else {
                    WRAPPER_CALL_TIMEOUT_SECONDS * 1000L
                },
            )
            if (completedFuture == null) {
                if (nextCandidateIndex < candidates.size) {
                    submitNextCandidate()
                    continue
                }
                break
            }

            active.remove(completedFuture)
            val completed = completedFuture.getNow(null)
            if (completed?.samples != null) {
                active.forEach { it.cancel(true) }
                return DecryptResult(completed.host, completed.samples)
            }

            val error = completed?.error
            errors += "${completed?.host ?: "unknown"}: ${error?.message ?: error?.javaClass?.simpleName ?: "unknown error"}"
            if (active.isEmpty() && nextCandidateIndex < candidates.size) {
                submitNextCandidate()
            }
        }

        active.forEach { it.cancel(true) }
        throw WrapperManagerException(
            "wrapper-manager Decrypt failed on every instance: ${errors.joinToString("; ")}"
        )
    }

    private fun awaitAnyDecryptCandidate(
        active: List<CompletableFuture<DecryptCandidateResult>>,
        timeoutMs: Long,
    ): CompletableFuture<DecryptCandidateResult>? {
        if (active.isEmpty()) return null
        active.firstOrNull { it.isDone }?.let { return it }
        val any = CompletableFuture.anyOf(*active.toTypedArray())
        return try {
            any.get(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            active.firstOrNull { it.isDone }
        } catch (error: Exception) {
            when (error) {
                is TimeoutException -> null
                is InterruptedException -> {
                    Thread.currentThread().interrupt()
                    throw WrapperManagerException("ALAC decrypt race was interrupted", error)
                }
                is ExecutionException -> {
                    active.firstOrNull { it.isDone }
                        ?: throw WrapperManagerException("ALAC decrypt race failed", error)
                }
                else -> {
                    Timber.tag(TAG).w(error, "Unexpected ALAC decrypt race failure")
                    throw WrapperManagerException(
                        "ALAC decrypt race failed: ${error.message ?: error.javaClass.simpleName}",
                        error,
                    )
                }
            }
        }
    }

    private class StreamingThenBatchSampleDecryptClient(
        host: String,
        secure: Boolean,
        mode: WrapperMode,
    ) : SampleDecryptClient {
        private val fallback = BatchSampleDecryptClient(host = host, secure = secure, mode = mode)
        private val streaming = runCatching {
            StreamingGrpcSampleDecryptClient(rawHost = host, secure = secure, mode = mode)
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "ALAC streaming decrypt client failed to initialize; using batch fallback")
        }.getOrNull()

        @Volatile
        private var closed = false

        @Volatile
        private var streamingDisabled = streaming == null

        override fun decryptSegment(
            adamId: String,
            key: String,
            samples: List<DecryptSample>,
            context: String?,
        ): Map<Int, ByteArray> {
            ensureOpen()
            val activeStreaming = streaming.takeIf { !streamingDisabled }
            if (activeStreaming != null) {
                runCatching {
                    return activeStreaming.decryptSegment(
                        adamId = adamId,
                        key = key,
                        samples = samples,
                        context = context,
                    )
                }.onFailure { error ->
                    streamingDisabled = true
                    fallback.deprioritizeHost(activeStreaming.host)
                    runCatching { activeStreaming.close() }
                    Timber.tag(TAG).w(
                        error,
                        "ALAC streaming decrypt failed; switching this session to batch fallback"
                    )
                }
            }
            return fallback.decryptSegment(
                adamId = adamId,
                key = key,
                samples = samples,
                context = context,
            )
        }

        override fun reportSegmentIntegrityFailure(context: String, reason: String): Boolean {
            val activeStreaming = streaming.takeIf { !streamingDisabled }
            val streamingRerouted = activeStreaming != null
            if (activeStreaming != null) {
                streamingDisabled = true
                fallback.deprioritizeHost(activeStreaming.host)
                runCatching { activeStreaming.close() }
                Timber.tag(TAG).w(
                    "ALAC streaming decrypt disabled after $context integrity failure: $reason"
                )
            }
            return fallback.reportSegmentIntegrityFailure(context, reason) || streamingRerouted
        }

        override fun clearSegmentContext(context: String) {
            fallback.clearSegmentContext(context)
        }

        override fun close() {
            closed = true
            runCatching { streaming?.close() }
            fallback.close()
        }

        private fun ensureOpen() {
            if (closed) throw WrapperManagerException("ALAC decrypt client was closed")
        }
    }

    private class StreamingGrpcSampleDecryptClient(
        rawHost: String,
        private val secure: Boolean,
        private val mode: WrapperMode,
    ) : SampleDecryptClient {
        val host: String = normalizeHost(rawHost)

        private val closed = AtomicBoolean(false)
        private val outboundFrames = LinkedBlockingQueue<ByteArray>()
        private val pending = ConcurrentHashMap<PendingDecryptKey, CompletableFuture<ByteArray>>()
        private val replyCount = AtomicInteger(0)
        private val grpcClients = GrpcClients(
            https = client.newBuilder()
                .readTimeout(0L, TimeUnit.MILLISECONDS)
                .callTimeout(0L, TimeUnit.MILLISECONDS)
                .build(),
            h2c = h2cGrpcClient.newBuilder()
                .readTimeout(0L, TimeUnit.MILLISECONDS)
                .callTimeout(0L, TimeUnit.MILLISECONDS)
                .build(),
        )
        private val streamExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "AppleWrapperDecryptStream").apply { isDaemon = true }
        }

        @Volatile
        private var currentCall: Call? = null

        @Volatile
        private var streamFailure: Throwable? = null

        private val readerTask = CompletableFuture.runAsync(
            { runDecryptStream() },
            streamExecutor,
        )

        override fun decryptSegment(
            adamId: String,
            key: String,
            samples: List<DecryptSample>,
            context: String?,
        ): Map<Int, ByteArray> {
            ensureOpen()
            if (samples.isEmpty()) return emptyMap()

            val repliesBefore = replyCount.get()
            val pendingForCall = LinkedHashMap<PendingDecryptKey, CompletableFuture<ByteArray>>()
            val sampleByKey = LinkedHashMap<PendingDecryptKey, DecryptSample>()
            try {
                samples.forEach { sample ->
                    val pendingKey = PendingDecryptKey(adamId, key, sample.sampleIndex)
                    val future = CompletableFuture<ByteArray>()
                    pending.put(pendingKey, future)?.completeExceptionally(
                        WrapperManagerException("wrapper-manager streaming decrypt sample ${sample.sampleIndex} was superseded")
                    )
                    pendingForCall[pendingKey] = future
                    sampleByKey[pendingKey] = sample
                    outboundFrames.put(
                        frameGrpcMessage(
                            encodeDecryptRequest(
                                adamId = adamId,
                                key = key,
                                sampleIndex = sample.sampleIndex,
                                sample = sample.data,
                            )
                        )
                    )
                }

                waitForFirstReplyOrFailure(repliesBefore, pendingForCall.values)
                return pendingForCall.mapValuesTo(linkedMapOf()) { (pendingKey, future) ->
                    val sample = sampleByKey.getValue(pendingKey)
                    val decrypted = awaitStreamingSample(pendingKey, future)
                    if (!sample.isUsableDecryptedSample(decrypted)) {
                        throw WrapperManagerException(
                            "wrapper-manager streaming did not return usable sample ${pendingKey.sampleIndex}"
                        )
                    }
                    decrypted
                }.mapKeys { it.key.sampleIndex }
            } finally {
                pendingForCall.keys.forEach { pending.remove(it) }
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                currentCall?.cancel()
                failPending(WrapperManagerException("ALAC streaming decrypt client was closed"))
                grpcClients.cancelAll()
                streamExecutor.shutdownNow()
            }
        }

        private fun runDecryptStream() {
            try {
                val request = Request.Builder()
                    .url(buildUrl(host, secure, mode.decryptRpcPath))
                    .header("Content-Type", "application/grpc")
                    .header("TE", "trailers")
                    .header("User-Agent", "Echo-TidalPlus/AppleMusicWrapperManager")
                    .post(StreamingGrpcRequestBody(outboundFrames, closed))
                    .build()
                val grpcClient = if (request.url.scheme == "http") grpcClients.h2c else grpcClients.https
                val call = grpcClient.newCall(request)
                currentCall = call
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        val preview = response.body?.string().orEmpty().take(240)
                        throw WrapperManagerException(
                            "wrapper-manager streaming HTTP ${response.code} at ${request.url.host}: $preview"
                        )
                    }
                    val source = response.body?.source()
                        ?: throw WrapperManagerException("wrapper-manager streaming response had no body")
                    while (!closed.get()) {
                        val payload = readGrpcFrame(source) ?: break
                        val reply = parseDecryptReply(payload)
                        replyCount.incrementAndGet()
                        val pendingKey = PendingDecryptKey(reply.adamId, reply.key, reply.sampleIndex)
                        val future = pending.remove(pendingKey)
                        if (future != null) {
                            future.complete(reply.data)
                        } else {
                            Timber.tag(TAG).d(
                                "ALAC streaming decrypt ignored unclaimed sample ${reply.sampleIndex} from $host"
                            )
                        }
                    }
                    if (!closed.get()) {
                        throw WrapperManagerException("wrapper-manager streaming decrypt ended")
                    }
                }
            } catch (error: Throwable) {
                if (!closed.get()) {
                    markStreamFailure(error)
                }
            } finally {
                closed.set(true)
            }
        }

        private fun waitForFirstReplyOrFailure(
            repliesBefore: Int,
            futures: Collection<CompletableFuture<ByteArray>>,
        ) {
            val deadline = System.currentTimeMillis() + STREAMING_FIRST_REPLY_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                ensureOpen()
                if (replyCount.get() > repliesBefore || futures.any { it.isDone }) return
                Thread.sleep(25L)
            }
            throw WrapperManagerException("wrapper-manager streaming did not return usable sample before fallback deadline")
        }

        private fun awaitStreamingSample(
            pendingKey: PendingDecryptKey,
            future: CompletableFuture<ByteArray>,
        ): ByteArray {
            return try {
                future.get(STREAMING_SAMPLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (error: TimeoutException) {
                throw WrapperManagerException(
                    "wrapper-manager streaming did not return usable sample ${pendingKey.sampleIndex}",
                    error,
                )
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw WrapperManagerException("ALAC streaming decrypt wait was interrupted", error)
            } catch (error: ExecutionException) {
                throw WrapperManagerException(
                    "ALAC streaming decrypt failed: ${error.cause?.message ?: error.message ?: "unknown error"}",
                    error.cause ?: error,
                )
            }
        }

        private fun ensureOpen() {
            if (closed.get()) throw WrapperManagerException("ALAC streaming decrypt client was closed")
            streamFailure?.let { error ->
                throw WrapperManagerException(
                    "ALAC streaming decrypt client failed: ${error.message ?: error.javaClass.simpleName}",
                    error,
                )
            }
            if (readerTask.isCompletedExceptionally) {
                throw WrapperManagerException("ALAC streaming decrypt reader failed")
            }
        }

        private fun markStreamFailure(error: Throwable) {
            streamFailure = error
            closed.set(true)
            failPending(error)
            Timber.tag(TAG).w(error, "ALAC streaming decrypt connection failed on $host")
        }

        private fun failPending(error: Throwable) {
            pending.entries.forEach { (key, future) ->
                if (pending.remove(key, future)) {
                    future.completeExceptionally(error)
                }
            }
        }
    }

    private class StreamingGrpcRequestBody(
        private val frames: LinkedBlockingQueue<ByteArray>,
        private val closed: AtomicBoolean,
    ) : RequestBody() {
        override fun contentType() = grpcMediaType

        override fun contentLength() = -1L

        override fun isDuplex() = true

        override fun writeTo(sink: BufferedSink) {
            while (true) {
                if (closed.get() && frames.isEmpty()) return
                val frame = frames.poll(STREAMING_WRITE_POLL_MS, TimeUnit.MILLISECONDS) ?: continue
                if (closed.get()) return
                sink.write(frame)
                sink.flush()
            }
        }
    }

    private class BatchSampleDecryptClient(
        private val host: String,
        private val secure: Boolean,
        private val mode: WrapperMode,
    ) : SampleDecryptClient {
        @Volatile
        private var closed = false
        private val deprioritizedHosts = ConcurrentHashMap.newKeySet<String>()
        private val successfulHostsByContext = ConcurrentHashMap<String, MutableSet<String>>()
        private val grpcClients = GrpcClients(
            https = client.newBuilder().build(),
            h2c = h2cGrpcClient.newBuilder().build(),
        )

        override fun decryptSegment(
            adamId: String,
            key: String,
            samples: List<DecryptSample>,
            context: String?,
        ): Map<Int, ByteArray> {
            ensureOpen()
            val result = decryptSegmentWithClients(
                host = host,
                secure = secure,
                mode = mode,
                adamId = adamId,
                key = key,
                samples = samples,
                grpcClients = grpcClients,
                deprioritizedHosts = deprioritizedHosts,
            )
            if (context != null) {
                successfulHostsByContext.computeIfAbsent(context) {
                    ConcurrentHashMap.newKeySet()
                }.add(normalizeHost(result.host))
            }
            Timber.tag(TAG).d(
                "ALAC decrypt context=${context ?: "direct"} host=${result.host} samples=${samples.size} " +
                    "deprioritized=${deprioritizedHosts.joinToString()}"
            )
            return result.samples
        }

        override fun reportSegmentIntegrityFailure(context: String, reason: String): Boolean {
            val hosts = successfulHostsByContext.remove(context).orEmpty()
            if (hosts.isEmpty()) {
                Timber.tag(TAG).w("ALAC segment integrity failure had no recorded decrypt host for $context: $reason")
                return false
            }
            hosts.forEach { host ->
                deprioritizedHosts.add(normalizeHost(host))
            }
            Timber.tag(TAG).w(
                "ALAC deprioritized wrapper host(s) ${hosts.joinToString()} after $context integrity failure: $reason"
            )
            return true
        }

        fun deprioritizeHost(host: String) {
            deprioritizedHosts.add(normalizeHost(host))
        }

        override fun clearSegmentContext(context: String) {
            successfulHostsByContext.remove(context)
        }

        override fun close() {
            closed = true
            successfulHostsByContext.clear()
            deprioritizedHosts.clear()
            grpcClients.cancelAll()
        }

        private fun ensureOpen() {
            if (closed) throw WrapperManagerException("ALAC decrypt client was closed")
        }
    }

    private fun decryptSegmentOnInstance(
        host: String,
        secure: Boolean,
        mode: WrapperMode,
        adamId: String,
        key: String,
        samples: List<DecryptSample>,
        grpcClients: GrpcClients,
    ): Map<Int, ByteArray> {
        if (samples.isEmpty()) return emptyMap()
        val decryptedByIndex = linkedMapOf<Int, ByteArray>()
        val retryErrors = mutableListOf<String>()

        samples.chunked(DECRYPT_BATCH_SIZE).forEach { chunk ->
            decryptedByIndex += usableDecryptedSamples(
                requested = chunk,
                decrypted = decryptSampleBatch(
                    host = host,
                    secure = secure,
                    mode = mode,
                    adamId = adamId,
                    key = key,
                    samples = chunk,
                    grpcClients = grpcClients,
                )
            )
        }

        for (attempt in 0 until DECRYPT_MISSING_RETRY_COUNT) {
            val missing = samples.filter { !decryptedByIndex.hasUsableSample(it) }
            if (missing.isEmpty()) break
            missing.forEach { decryptedByIndex.remove(it.sampleIndex) }
            if (attempt > 0) {
                Thread.sleep(DECRYPT_MISSING_RETRY_DELAY_MS * attempt)
            }

            missing.chunked(DECRYPT_RETRY_BATCH_SIZE).forEach { retryChunk ->
                val recovered = runCatching {
                    usableDecryptedSamples(
                        requested = retryChunk,
                        decrypted = decryptSampleBatch(
                            host = host,
                            secure = secure,
                            mode = mode,
                            adamId = adamId,
                            key = key,
                            samples = retryChunk,
                            grpcClients = grpcClients,
                        )
                    )
                }.onFailure { error ->
                    val message =
                        "batch retry failed for samples ${retryChunk.first().sampleIndex}-${retryChunk.last().sampleIndex}: ${error.message ?: error.javaClass.simpleName}"
                    retryErrors.add(message)
                    Timber.tag(TAG).w(error, "ALAC $message")
                }.getOrNull()
                if (recovered.isNullOrEmpty()) {
                    retryErrors.add(
                        "batch retry returned no usable samples for ${retryChunk.first().sampleIndex}-${retryChunk.last().sampleIndex}"
                    )
                } else {
                    decryptedByIndex += recovered
                }
            }

            samples.filter { !decryptedByIndex.hasUsableSample(it) }.forEach { sample ->
                decryptedByIndex.remove(sample.sampleIndex)
                runCatching {
                    decryptSingleSample(
                        host = host,
                        secure = secure,
                        mode = mode,
                        adamId = adamId,
                        key = key,
                        sample = sample,
                        duplicates = DECRYPT_SINGLE_SAMPLE_DUPLICATES + attempt.coerceAtMost(4),
                        grpcClients = grpcClients,
                    )
                }.onFailure { error ->
                    val message = "single retry failed for sample ${sample.sampleIndex}: ${error.message ?: error.javaClass.simpleName}"
                    retryErrors.add(message)
                    Timber.tag(TAG).w(error, "ALAC $message")
                }.getOrNull()
                    ?.takeIf { sample.isUsableDecryptedSample(it) }
                    ?.let { decrypted ->
                        decryptedByIndex[sample.sampleIndex] = decrypted
                    }
            }
        }

        samples.filter { !decryptedByIndex.hasUsableSample(it) }.forEach { sample ->
            decryptedByIndex.remove(sample.sampleIndex)
            repeat(DECRYPT_FINAL_SINGLE_ROUNDS) { round ->
                if (decryptedByIndex.hasUsableSample(sample)) return@repeat
                if (round > 0) Thread.sleep(DECRYPT_MISSING_RETRY_DELAY_MS * (round + 1))
                runCatching {
                    decryptSingleSample(
                        host = host,
                        secure = secure,
                        mode = mode,
                        adamId = adamId,
                        key = key,
                        sample = sample,
                        duplicates = DECRYPT_SINGLE_SAMPLE_DUPLICATES + DECRYPT_FINAL_SINGLE_ROUNDS,
                        grpcClients = grpcClients,
                    )
                }.onFailure { error ->
                    val message = "final single retry failed for sample ${sample.sampleIndex}: ${error.message ?: error.javaClass.simpleName}"
                    retryErrors.add(message)
                    Timber.tag(TAG).w(error, "ALAC $message")
                }.getOrNull()
                    ?.takeIf { sample.isUsableDecryptedSample(it) }
                    ?.let { decrypted ->
                        decryptedByIndex[sample.sampleIndex] = decrypted
                    }
            }
        }

        val missing = samples.firstOrNull { !decryptedByIndex.hasUsableSample(it) }
        if (missing != null) {
            throw WrapperManagerException(
                buildString {
                    append("wrapper-manager Decrypt did not return usable sample ${missing.sampleIndex}")
                    if (retryErrors.isNotEmpty()) {
                        append("; retry errors: ")
                        append(retryErrors.takeLast(4).joinToString(" | "))
                    }
                }
            )
        }
        return decryptedByIndex
    }

    private fun usableDecryptedSamples(
        requested: List<DecryptSample>,
        decrypted: Map<Int, ByteArray>,
    ): Map<Int, ByteArray> {
        return requested.mapNotNull { sample ->
            val bytes = decrypted[sample.sampleIndex]
            if (bytes != null && sample.isUsableDecryptedSample(bytes)) sample.sampleIndex to bytes
            else null
        }.toMap()
    }

    private fun Map<Int, ByteArray>.hasUsableSample(sample: DecryptSample): Boolean {
        return this[sample.sampleIndex]?.let { sample.isUsableDecryptedSample(it) } == true
    }

    private fun DecryptSample.isUsableDecryptedSample(decrypted: ByteArray): Boolean {
        if (decrypted.isEmpty()) return false
        if (decrypted.size != data.size) return false
        if (data.size >= MIN_UNDECRYPTED_COMPARE_BYTES && decrypted.contentEquals(data)) return false
        return true
    }

    private fun decryptSingleSample(
        host: String,
        secure: Boolean,
        mode: WrapperMode,
        adamId: String,
        key: String,
        sample: DecryptSample,
        duplicates: Int = DECRYPT_SINGLE_SAMPLE_DUPLICATES,
        grpcClients: GrpcClients = defaultGrpcClients,
    ): ByteArray? {
        val repeatedSample = List(duplicates.coerceAtLeast(1)) { sample }
        return decryptSampleBatch(
            host = host,
            secure = secure,
            mode = mode,
            adamId = adamId,
            key = key,
            samples = repeatedSample,
            grpcClients = grpcClients,
        )[sample.sampleIndex]
    }

    private fun decryptSampleBatch(
        host: String,
        secure: Boolean,
        mode: WrapperMode,
        adamId: String,
        key: String,
        samples: List<DecryptSample>,
        grpcClients: GrpcClients = defaultGrpcClients,
    ): Map<Int, ByteArray> {
        if (samples.isEmpty()) return emptyMap()
        val framesToSend = samples + samples.last()
        val framedPayload = ByteArrayOutputStream().apply {
            framesToSend.forEach { sample ->
                write(
                    frameGrpcMessage(
                        encodeDecryptRequest(
                            adamId = adamId,
                            key = key,
                            sampleIndex = sample.sampleIndex,
                            sample = sample.data
                        )
                    )
                )
            }
        }.toByteArray()
        return callStreaming(
            url = buildUrl(host, secure, mode.decryptRpcPath),
            framedPayload = framedPayload,
            grpcClients = grpcClients,
        ).map { parseDecryptReply(it) }
            .associate { it.sampleIndex to it.data }
    }

    fun decrypt(
        host: String,
        secure: Boolean,
        mode: WrapperMode,
        adamId: String,
        key: String,
        sampleIndex: Int,
        data: ByteArray,
    ): ByteArray {
        return decryptSegment(
            host = host,
            secure = secure,
            mode = mode,
            adamId = adamId,
            key = key,
            samples = listOf(DecryptSample(sampleIndex, data))
        ).getValue(sampleIndex)
    }

    @Deprecated("Wrapper decrypt needs adamId, key, and sampleIndex. Use decryptSegment instead.")
    @Suppress("UNUSED_PARAMETER")
    fun decrypt(
        host: String,
        secure: Boolean,
        mode: WrapperMode,
        data: ByteArray,
        keyUri: String? = null,
    ): ByteArray {
        throw WrapperManagerException(
            "wrapper-manager Decrypt requires adamId and sampleIndex; use decryptSegment"
        )
    }

    private fun callUnary(
        url: String,
        payload: ByteArray,
        grpcClients: GrpcClients = unaryGrpcClients,
    ): ByteArray {
        val frames = callStreaming(
            url = url,
            framedPayload = frameGrpcMessage(payload),
            grpcClients = grpcClients,
        )
        return frames.firstOrNull()
            ?: throw WrapperManagerException("wrapper-manager returned no gRPC messages")
    }

    private fun callStreaming(
        url: String,
        framedPayload: ByteArray,
        grpcClients: GrpcClients = defaultGrpcClients,
    ): List<ByteArray> {
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/grpc")
            .header("TE", "trailers")
            .header("User-Agent", "Echo-TidalPlus/AppleMusicWrapperManager")
            .post(framedPayload.toRequestBody(grpcMediaType))
            .build()

        val grpcClient = if (request.url.scheme == "http") grpcClients.h2c else grpcClients.https
        grpcClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val preview = response.body?.string().orEmpty().take(240)
                throw WrapperManagerException(
                    "wrapper-manager HTTP ${response.code} at ${request.url.host}: $preview"
                )
            }
            val body = (response.body
                ?: throw WrapperManagerException("wrapper-manager response had no body"))
                .bytes()
            return unframeGrpcMessages(body)
        }
    }

    private fun downloadPlaylist(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Echo-TidalPlus/AppleMusicWrapperManager")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw WrapperManagerException(
                    "wrapper-manager HLS check failed with HTTP ${response.code} at ${request.url.host}"
                )
            }
            return (response.body
                ?: throw WrapperManagerException("wrapper-manager HLS check response had no body"))
                .string()
        }
    }

    private fun String.hasProtectedAppleKeys(): Boolean {
        return lineSequence().any { rawLine ->
            val line = rawLine.trim()
            (line.startsWith("#EXT-X-KEY:", ignoreCase = true) ||
                line.startsWith("#EXT-X-SESSION-KEY:", ignoreCase = true)) &&
                !line.contains("METHOD=NONE", ignoreCase = true)
        }
    }

    private fun String.firstChildPlaylistUrl(rootUrl: String): String? {
        val root = rootUrl.toHttpUrlOrNull() ?: return null
        return lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .firstOrNull { it.endsWith(".m3u8", ignoreCase = true) }
            ?.let { root.resolve(it)?.toString() }
    }

    private fun encryptedHlsException(mode: WrapperMode): WrapperManagerException {
        return WrapperManagerException(
            "${mode.title} returned protected Apple HLS. Metrolist cannot direct-play this wrapper-manager URL; " +
                "AppleMusicDecrypt downloads, decrypts, and remuxes it before playback. Use the am-dl Apple option " +
                "for now, or wire the full decrypt/remux pipeline before enabling wrapper playback."
        )
    }

    private fun buildInstanceCandidates(
        preferredHost: String?,
        preferredSecure: Boolean,
        deprioritizedHosts: Set<String> = emptySet(),
    ): List<Pair<String, Boolean>> {
        val candidates = buildList {
            val normalizedPreferred = normalizeHost(preferredHost)
            add(normalizedPreferred to preferredSecure)
            DEFAULT_INSTANCES.forEach { instance ->
                val normalized = normalizeHost(instance.first)
                if (none { it.first == normalized }) {
                    add(normalized to instance.second)
                }
            }
        }
        val normalizedDeprioritized = deprioritizedHosts.mapTo(mutableSetOf()) { normalizeHost(it) }
        val preferred = candidates.filterNot { it.first in normalizedDeprioritized }
        if (preferred.isEmpty()) return candidates
        return preferred + candidates.filter { it.first in normalizedDeprioritized }
    }

    private fun encodeM3u8Request(adamId: String): ByteArray {
        val data = ProtoWriter().apply { string(1, adamId) }.toByteArray()
        return ProtoWriter().apply { message(1, data) }.toByteArray()
    }

    private fun encodeWebPlaybackRequest(adamId: String): ByteArray {
        val data = ProtoWriter().apply { string(1, adamId) }.toByteArray()
        return ProtoWriter().apply { message(1, data) }.toByteArray()
    }

    private fun encodeDecryptRequest(
        adamId: String,
        key: String,
        sampleIndex: Int,
        sample: ByteArray,
    ): ByteArray {
        val data = ProtoWriter().apply {
            string(1, adamId)
            string(2, key)
            int32(3, sampleIndex)
            bytes(4, sample)
        }.toByteArray()
        return ProtoWriter().apply { message(1, data) }.toByteArray()
    }

    private fun parseDecryptReply(bytes: ByteArray): DecryptReplySample {
        val reader = ProtoReader(bytes)
        var code = 0
        var message = ""
        var adamId = ""
        var key = ""
        var sampleIndex = 0
        var decrypted = ByteArray(0)
        while (!reader.done()) {
            when (val field = reader.nextField()) {
                1 -> {
                    val header = parseHeader(reader.bytes())
                    code = header.first
                    message = header.second
                }
                2 -> {
                    val data = ProtoReader(reader.bytes())
                    while (!data.done()) {
                        when (data.nextField()) {
                            1 -> adamId = data.string()
                            2 -> key = data.string()
                            3 -> sampleIndex = data.varint().toInt()
                            4 -> decrypted = data.bytes()
                            else -> data.skip()
                        }
                    }
                }
                else -> reader.skip(field)
            }
        }
        if (code != 0) {
            throw WrapperManagerException(
                "wrapper-manager Decrypt failed: ${message.ifBlank { "code $code" }}"
            )
        }
        if (decrypted.isEmpty()) {
            throw WrapperManagerException("wrapper-manager Decrypt returned an empty sample")
        }
        return DecryptReplySample(adamId, key, sampleIndex, decrypted)
    }

    private fun frameGrpcMessage(payload: ByteArray): ByteArray {
        return ByteArrayOutputStream(payload.size + 5).apply {
            write(0)
            write((payload.size ushr 24) and 0xff)
            write((payload.size ushr 16) and 0xff)
            write((payload.size ushr 8) and 0xff)
            write(payload.size and 0xff)
            write(payload)
        }.toByteArray()
    }

    private fun unframeGrpcMessages(bytes: ByteArray): List<ByteArray> {
        val messages = mutableListOf<ByteArray>()
        var index = 0
        while (index < bytes.size) {
            if (bytes.size - index < 5) {
                throw WrapperManagerException("wrapper-manager response had a partial gRPC frame")
            }
            val compressed = bytes[index].toInt() != 0
            if (compressed) {
                throw WrapperManagerException("wrapper-manager returned compressed gRPC data")
            }
            val length =
                ((bytes[index + 1].toInt() and 0xff) shl 24) or
                    ((bytes[index + 2].toInt() and 0xff) shl 16) or
                    ((bytes[index + 3].toInt() and 0xff) shl 8) or
                    (bytes[index + 4].toInt() and 0xff)
            if (length < 0 || index + 5 + length > bytes.size) {
                throw WrapperManagerException("wrapper-manager response length was invalid")
            }
            messages += bytes.copyOfRange(index + 5, index + 5 + length)
            index += 5 + length
        }
        return messages
    }

    private fun readGrpcFrame(source: BufferedSource): ByteArray? {
        return try {
            val compressed = source.readByte().toInt() != 0
            if (compressed) {
                throw WrapperManagerException("wrapper-manager returned compressed gRPC data")
            }
            val length = source.readInt()
            if (length < 0) {
                throw WrapperManagerException("wrapper-manager response length was invalid")
            }
            source.readByteArray(length.toLong())
        } catch (error: EOFException) {
            null
        }
    }

    private fun parseM3u8LikeReply(bytes: ByteArray): M3u8LikeReply {
        val reader = ProtoReader(bytes)
        var code = 0
        var message = ""
        var m3u8 = ""
        while (!reader.done()) {
            when (val field = reader.nextField()) {
                1 -> {
                    val header = parseHeader(reader.bytes())
                    code = header.first
                    message = header.second
                }
                2 -> {
                    val data = ProtoReader(reader.bytes())
                    while (!data.done()) {
                        when (data.nextField()) {
                            2 -> m3u8 = data.string()
                            else -> data.skip()
                        }
                    }
                }
                else -> reader.skip(field)
            }
        }
        return M3u8LikeReply(code, message, m3u8)
    }

    private fun parseStatusReply(bytes: ByteArray): WrapperStatus {
        val reader = ProtoReader(bytes)
        var code = 0
        var message = ""
        var ready = false
        val regions = mutableListOf<String>()
        var clientCount = 0
        while (!reader.done()) {
            when (val field = reader.nextField()) {
                1 -> {
                    val header = parseHeader(reader.bytes())
                    code = header.first
                    message = header.second
                }
                2 -> {
                    val data = ProtoReader(reader.bytes())
                    while (!data.done()) {
                        when (data.nextField()) {
                            2 -> regions += data.string()
                            3 -> clientCount = data.varint().toInt()
                            4 -> ready = data.varint() != 0L
                            else -> data.skip()
                        }
                    }
                }
                else -> reader.skip(field)
            }
        }
        if (code != 0) throw WrapperManagerException("wrapper-manager status failed: $message")
        return WrapperStatus(ready = ready, regions = regions, clientCount = clientCount)
    }

    private fun parseHeader(bytes: ByteArray): Pair<Int, String> {
        val reader = ProtoReader(bytes)
        var code = 0
        var message = ""
        while (!reader.done()) {
            when (reader.nextField()) {
                1 -> code = reader.varint().toInt()
                2 -> message = reader.string()
                else -> reader.skip()
            }
        }
        return code to message
    }

    data class WrapperStatus(
        val ready: Boolean,
        val regions: List<String>,
        val clientCount: Int,
    )

    private data class M3u8LikeReply(
        val code: Int,
        val message: String,
        val m3u8: String,
    )

    private val DEFAULT_INSTANCES = listOf(
        DEFAULT_HOST to true,
        "wm1.wol.moe" to true,
    )

    private class ProtoWriter {
        private val out = ByteArrayOutputStream()

        fun string(field: Int, value: String) {
            bytes(field, value.toByteArray(Charsets.UTF_8))
        }

        fun message(field: Int, value: ByteArray) {
            bytes(field, value)
        }

        fun bytes(field: Int, value: ByteArray) {
            tag(field, 2)
            varint(value.size.toLong())
            out.write(value)
        }

        fun int32(field: Int, value: Int) {
            tag(field, 0)
            varint(value.toLong())
        }

        private fun tag(field: Int, wireType: Int) {
            varint(((field shl 3) or wireType).toLong())
        }

        private fun varint(value: Long) {
            var current = value
            while (true) {
                if ((current and 0x7f.inv().toLong()) == 0L) {
                    out.write(current.toInt())
                    return
                }
                out.write(((current and 0x7f) or 0x80).toInt())
                current = current ushr 7
            }
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private class ProtoReader(private val bytes: ByteArray) {
        private var index = 0
        private var wireType = 0

        fun done() = index >= bytes.size

        fun nextField(): Int {
            val tag = varint().toInt()
            wireType = tag and 7
            return tag ushr 3
        }

        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (index < bytes.size) {
                val b = bytes[index++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if ((b and 0x80) == 0) return result
                shift += 7
            }
            throw WrapperManagerException("Invalid protobuf varint")
        }

        fun string(): String = bytes().toString(Charsets.UTF_8)

        fun bytes(): ByteArray {
            val length = varint().toInt()
            if (length < 0 || index + length > bytes.size) {
                throw WrapperManagerException("Invalid protobuf length")
            }
            return bytes.copyOfRange(index, index + length).also { index += length }
        }

        fun skip(field: Int = 0) {
            when (wireType) {
                0 -> varint()
                1 -> index += 8
                2 -> {
                    val length = varint().toInt()
                    index += length
                }
                5 -> index += 4
                else -> throw WrapperManagerException("Unsupported protobuf wire type $wireType on field $field")
            }
            if (index > bytes.size) throw WrapperManagerException("Invalid protobuf skip")
        }
    }
}

