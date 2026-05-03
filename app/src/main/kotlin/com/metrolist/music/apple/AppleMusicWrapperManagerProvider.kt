package com.metrolist.music.apple

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object AppleMusicWrapperManagerProvider {
    private const val DECRYPT_BATCH_SIZE = 96
    private const val DECRYPT_RETRY_BATCH_SIZE = 12
    private const val DECRYPT_MISSING_RETRY_COUNT = 10
    private const val DECRYPT_SINGLE_SAMPLE_DUPLICATES = 6
    private const val DECRYPT_FINAL_SINGLE_ROUNDS = 6
    private const val DECRYPT_MISSING_RETRY_DELAY_MS = 180L
    private const val MIN_UNDECRYPTED_COMPARE_BYTES = 32

    enum class WrapperMode(
        val idSuffix: String,
        val title: String,
        val quality: Int,
        val m3u8RpcPath: String,
        val decryptRpcPath: String,
        val requestKind: RequestKind,
    ) {
        ALAC(
            idSuffix = "alac",
            title = "Apple Music ALAC (Wrapper)",
            quality = 11,
            m3u8RpcPath = "/manager.v1.WrapperManagerService/M3U8",
            decryptRpcPath = "/manager.v1.WrapperManagerService/Decrypt",
            requestKind = RequestKind.M3U8
        ),
        AAC(
            idSuffix = "aac",
            title = "Apple Music AAC (Wrapper)",
            quality = 8,
            m3u8RpcPath = "/manager.v1.WrapperManagerService/WebPlayback",
            decryptRpcPath = "/manager.v1.WrapperManagerService/Decrypt",
            requestKind = RequestKind.WEB_PLAYBACK
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

    data class DecryptSample(
        val sampleIndex: Int,
        val data: ByteArray,
    )

    private data class DecryptReplySample(
        val adamId: String,
        val key: String,
        val sampleIndex: Int,
        val data: ByteArray,
    )

    private val grpcMediaType = "application/grpc".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val h2cGrpcClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    fun normalizeHost(raw: String?): String {
        return raw.orEmpty()
            .trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trim('/')
            .takeIf { it.isNotBlank() }
            ?: "wm.wol.moe"
    }

    fun buildUrl(host: String, secure: Boolean, path: String): String {
        val normalized = normalizeHost(host)
        val scheme = if (secure) "https" else "http"
        return "$scheme://$normalized$path"
    }

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
            payload = payload
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
            payload = ByteArray(0)
        )
        return parseStatusReply(responseBytes)
    }

    fun decryptSegment(
        host: String,
        secure: Boolean,
        mode: WrapperMode,
        adamId: String,
        key: String,
        samples: List<DecryptSample>,
    ): Map<Int, ByteArray> {
        val candidates = buildInstanceCandidates(host, secure)
        val errors = mutableListOf<String>()
        candidates.forEach { (candidateHost, candidateSecure) ->
            runCatching {
                decryptSegmentOnInstance(
                    host = candidateHost,
                    secure = candidateSecure,
                    mode = mode,
                    adamId = adamId,
                    key = key,
                    samples = samples
                )
            }.onSuccess { return it }
                .onFailure { error ->
                    errors += "$candidateHost: ${error.message ?: error.javaClass.simpleName}"
                }
        }

        throw WrapperManagerException(
            "wrapper-manager Decrypt failed on every instance: ${errors.joinToString("; ")}"
        )
    }

    private fun decryptSegmentOnInstance(
        host: String,
        secure: Boolean,
        mode: WrapperMode,
        adamId: String,
        key: String,
        samples: List<DecryptSample>,
    ): Map<Int, ByteArray> {
        if (samples.isEmpty()) return emptyMap()
        val decryptedByIndex = linkedMapOf<Int, ByteArray>()

        samples.chunked(DECRYPT_BATCH_SIZE).forEach { chunk ->
            decryptedByIndex += usableDecryptedSamples(
                requested = chunk,
                decrypted = decryptSampleBatch(
                    host = host,
                    secure = secure,
                    mode = mode,
                    adamId = adamId,
                    key = key,
                    samples = chunk
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
                runCatching {
                    usableDecryptedSamples(
                        requested = retryChunk,
                        decrypted = decryptSampleBatch(
                            host = host,
                            secure = secure,
                            mode = mode,
                            adamId = adamId,
                            key = key,
                            samples = retryChunk
                        )
                    )
                }.getOrNull()?.let { decryptedByIndex += it }
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
                        duplicates = DECRYPT_SINGLE_SAMPLE_DUPLICATES + attempt.coerceAtMost(4)
                    )
                }.getOrNull()?.takeIf { sample.isUsableDecryptedSample(it) }?.let { decrypted ->
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
                        duplicates = DECRYPT_SINGLE_SAMPLE_DUPLICATES + DECRYPT_FINAL_SINGLE_ROUNDS
                    )
                }.getOrNull()?.takeIf { sample.isUsableDecryptedSample(it) }?.let { decrypted ->
                    decryptedByIndex[sample.sampleIndex] = decrypted
                }
            }
        }

        val missing = samples.firstOrNull { !decryptedByIndex.hasUsableSample(it) }
        if (missing != null) {
            throw WrapperManagerException(
                "wrapper-manager Decrypt did not return usable sample ${missing.sampleIndex}"
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
    ): ByteArray? {
        val repeatedSample = List(duplicates.coerceAtLeast(1)) { sample }
        return decryptSampleBatch(
            host = host,
            secure = secure,
            mode = mode,
            adamId = adamId,
            key = key,
            samples = repeatedSample
        )[sample.sampleIndex]
    }

    private fun decryptSampleBatch(
        host: String,
        secure: Boolean,
        mode: WrapperMode,
        adamId: String,
        key: String,
        samples: List<DecryptSample>,
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
            framedPayload = framedPayload
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

    private fun callUnary(url: String, payload: ByteArray): ByteArray {
        val frames = callStreaming(
            url = url,
            framedPayload = frameGrpcMessage(payload)
        )
        return frames.firstOrNull()
            ?: throw WrapperManagerException("wrapper-manager returned no gRPC messages")
    }

    private fun callStreaming(url: String, framedPayload: ByteArray): List<ByteArray> {
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/grpc")
            .header("TE", "trailers")
            .header("User-Agent", "Echo-TidalPlus/AppleMusicWrapperManager")
            .post(framedPayload.toRequestBody(grpcMediaType))
            .build()

        val grpcClient = if (request.url.scheme == "http") h2cGrpcClient else client
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
    ): List<Pair<String, Boolean>> {
        return buildList {
            val normalizedPreferred = normalizeHost(preferredHost)
            add(normalizedPreferred to preferredSecure)
            DEFAULT_INSTANCES.forEach { instance ->
                val normalized = normalizeHost(instance.first)
                if (none { it.first == normalized }) {
                    add(normalized to instance.second)
                }
            }
        }
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
        "wm.wol.moe" to true,
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

