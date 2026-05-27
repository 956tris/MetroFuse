package com.metrolist.music.apple

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.max
import kotlin.math.min

object AppleMusicDecryptPipeline {
    private const val USER_AGENT = "Echo-TidalPlus/AppleMusicWrapperManager"
    private const val PREFETCH_KEY = "skd://itunes.apple.com/P000000000/s1/e1"
    private const val KEY_SUFFIX_DEFAULT = "c6"
    private const val DEFAULT_PREFETCH_WINDOW_SEGMENTS = 3
    private const val DEFAULT_PREFETCH_CONCURRENCY = 2
    private const val DEFAULT_STARTUP_PREFETCH_WINDOW_SEGMENTS = 2
    private const val DEFAULT_STARTUP_READY_SEGMENTS = 0
    private const val HIGH_PREFETCH_WINDOW_SEGMENTS = 5
    private const val HIGH_PREFETCH_CONCURRENCY = 3
    private const val HIGH_STARTUP_PREFETCH_WINDOW_SEGMENTS = 3
    private const val HIGH_STARTUP_READY_SEGMENTS = 0
    private const val MAX_ROLLING_CACHE_BYTES = 8 * 1024 * 1024
    private const val MAX_ENCRYPTED_SEGMENT_CACHE_BYTES = 4 * 1024 * 1024
    private const val SEGMENT_SLOW_MS = 4_000L
    private const val SEGMENT_TIMEOUT_MS = 10_000L
    private const val SEGMENT_INTEGRITY_RETRY_COUNT = 2
    private const val CBCS_BLOCK_SIZE = 16
    private const val ALAC_TYPE_END = 7
    private const val STARTUP_SLOW_MS = 8_000L
    private const val STARTUP_TIMEOUT_MS = 12_000L
    private const val TRACE_TAG = "AppleALAC"
    private const val VIRTUAL_HLS_MASTER_RESOURCE = "master.m3u8"
    private const val VIRTUAL_HLS_MEDIA_RESOURCE = "media.m3u8"
    private const val VIRTUAL_HLS_INIT_RESOURCE = "init.mp4"
    private const val VIRTUAL_HLS_SEGMENT_PREFIX = "seg-"
    private const val VIRTUAL_HLS_SEGMENT_SUFFIX = ".m4s"
    private const val VIRTUAL_HLS_SESSION_TTL_MS = 2 * 60 * 1000L
    private const val MAX_VIRTUAL_HLS_SESSIONS = 2
    private val segmentSampleCountCache = ConcurrentHashMap<String, Int>()
    private val segmentLengthCache = ConcurrentHashMap<String, Long>()
    private val encryptedSegmentCache = ConcurrentHashMap<String, ByteArray>()
    private val virtualHlsSessions = ConcurrentHashMap<String, AppleMusicAlacVirtualHlsSession>()

    class AlacStartupTimeoutException(message: String, cause: Throwable? = null) : IOException(message, cause)
    class AlacSegmentTimeoutException(message: String, cause: Throwable? = null) : IOException(message, cause)
    class AlacSegmentIntegrityException(message: String, cause: Throwable? = null) : IOException(message, cause)

    fun isAlacStartupTimeout(error: Throwable?): Boolean {
        return isAlacTimeout(error)
    }

    fun isAlacTimeout(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            if (current is AlacStartupTimeoutException) return true
            if (current is AlacSegmentTimeoutException) return true
            if (current.message?.contains("ALAC startup timed out", ignoreCase = true) == true) return true
            if (current.message?.contains("ALAC segment timed out", ignoreCase = true) == true) return true
            current = current.cause
        }
        return false
    }

    fun isAlacIntegrityError(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            if (current is AlacSegmentIntegrityException) return true
            val message = current.message
            if (
                message?.contains("ALAC segment", ignoreCase = true) == true &&
                (
                    message.contains("integrity", ignoreCase = true) ||
                        message.contains("container validation", ignoreCase = true) ||
                        message.contains("unsupported atom length", ignoreCase = true) ||
                        message.contains("invalid atom", ignoreCase = true)
                )
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    data class AlacQualityMetadata(
        val bitrate: Int = 0,
        val sampleRate: Int? = null,
        val bitDepth: Int? = null,
    ) {
        fun hasAny() = bitrate > 0 || sampleRate != null || bitDepth != null

        fun toLabel(): String? {
            val parts = listOfNotNull(
                sampleRate?.let { "${formatKhz(it)}kHz" },
                bitrate.takeIf { it > 0 }?.let { "${(it / 1000).coerceAtLeast(1)}kbps" },
                bitDepth?.let { "${it}-bit" }
            )
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
        }

        private fun formatKhz(value: Int): String {
            val khz = value / 1000.0
            return if (value % 1000 == 0) {
                String.format(Locale.US, "%.0f", khz)
            } else {
                String.format(Locale.US, "%.1f", khz)
            }
        }
    }

    data class VirtualHlsRequest(
        val sessionKey: String,
        val mediaId: String,
        val adamId: String,
        val m3u8Url: String,
        val host: String,
        val secure: Boolean,
        val mode: AppleMusicWrapperManagerProvider.WrapperMode,
        val durationMs: Long?,
        val highWorkerMode: Boolean,
    )

    fun clearMemoryCaches() {
        virtualHlsSessions.values.forEach { session ->
            runCatching { session.close() }
        }
        virtualHlsSessions.clear()
        segmentSampleCountCache.clear()
        segmentLengthCache.clear()
        encryptedSegmentCache.clear()
    }

    fun openVirtualHlsResource(
        request: VirtualHlsRequest,
        resourceName: String,
        resourceUri: (String) -> String,
        client: OkHttpClient,
        highWorkerMode: Boolean = false,
    ): ByteArray {
        closeStaleVirtualHlsSessions()
        val session = virtualHlsSessions.compute(request.sessionKey) { _, existing ->
            existing?.takeIf { !it.isClosed } ?: AppleMusicAlacVirtualHlsSession(
                request = request,
                client = client,
                prefetchWindow = if (highWorkerMode) HIGH_PREFETCH_WINDOW_SEGMENTS else DEFAULT_PREFETCH_WINDOW_SEGMENTS,
                prefetchConcurrency = if (highWorkerMode) HIGH_PREFETCH_CONCURRENCY else DEFAULT_PREFETCH_CONCURRENCY,
                resourceUri = resourceUri,
            )
        } ?: throw AppleMusicWrapperManagerProvider.WrapperManagerException("Failed to open ALAC virtual HLS session")
        return session.readResource(resourceName)
    }

    private fun closeStaleVirtualHlsSessions() {
        val now = System.currentTimeMillis()
        virtualHlsSessions.entries
            .filter { (_, session) -> session.isClosed || now - session.lastAccessMs > VIRTUAL_HLS_SESSION_TTL_MS }
            .forEach { (key, session) ->
                if (virtualHlsSessions.remove(key, session)) {
                    runCatching { session.close() }
                }
            }
        if (virtualHlsSessions.size <= MAX_VIRTUAL_HLS_SESSIONS) return
        virtualHlsSessions.entries
            .sortedBy { it.value.lastAccessMs }
            .take((virtualHlsSessions.size - MAX_VIRTUAL_HLS_SESSIONS).coerceAtLeast(0))
            .forEach { (key, session) ->
                if (virtualHlsSessions.remove(key, session)) {
                    runCatching { session.close() }
                    Timber.tag(TRACE_TAG).d("Closed old ALAC virtual HLS session for mediaId=${session.mediaId}")
                }
            }
    }

    fun openDecryptedStream(
        adamId: String,
        m3u8Url: String,
        host: String,
        secure: Boolean,
        mode: AppleMusicWrapperManagerProvider.WrapperMode,
        client: OkHttpClient,
        start: Long,
        requestedLength: Long = -1L,
        durationMs: Long? = null,
        highWorkerMode: Boolean = false,
    ): Pair<InputStream, Long> {
        val trace = AlacTrace(adamId = adamId, startOffset = start.coerceAtLeast(0L))
        val mediaDocument = trace.measure("media_playlist_fetch") {
            resolveToMediaPlaylist(client, m3u8Url, mode)
        }
        val mediaPlaylist = trace.measure("media_playlist_parse") {
            parseMediaPlaylist(mediaDocument.url, mediaDocument.text, mode, mediaDocument.qualityInfo)
        }
        trace.mark("duration_known", mediaPlaylist.durationMs ?: durationMs)
        val segmentLengths = trace.measure("seek_segment_lengths") {
            resolveSegmentLengths(client, mediaPlaylist.segments)
        }.also { lengths ->
            if (lengths == null) {
                trace.mark("seek_segment_lengths_unavailable")
            }
        }
        val rawInitBytes = trace.measure("init_segment_fetch") {
            downloadBytes(client, mediaPlaylist.init.url, mediaPlaylist.init.range)
        }
        val initQuality = readInitSegmentQuality(rawInitBytes)
        initQuality?.let { quality ->
            trace.mark("format_known", quality.toTraceValue())
        }
        val realQuality = mediaPlaylist.qualityInfo.mergedWith(initQuality)
        val averageBitrate =
            realQuality
                ?.bandwidth
                ?.takeIf { isPlausibleAlacBandwidth(it, realQuality.sampleRate) }
        val alacRepairParams =
            if (mode == AppleMusicWrapperManagerProvider.WrapperMode.ALAC) {
                readAlacRepairParams(rawInitBytes)
                    ?.also { trace.mark("alac_repair_ready", "sampleSize=${it.sampleSize} channels=${it.channels}") }
            } else {
                null
            }
        val patchedInitBytes = patchInitSegment(
            initBytes = rawInitBytes,
            mode = mode,
            durationMs = durationMs?.takeIf { it > 0L } ?: mediaPlaylist.durationMs,
            averageBitrate = averageBitrate
        )
        val initBytes = buildSeekableInitSegment(patchedInitBytes, mediaPlaylist, segmentLengths)
        val totalLength = segmentLengths?.fold(initBytes.size.toLong()) { total, length -> total + length }
        val decryptClient = AppleMusicWrapperManagerProvider.openSampleDecryptClient(
            host = host,
            secure = secure,
            mode = mode,
        )

        val stream = AppleMusicAlacStreamSession(
            initBytes = initBytes,
            playlist = mediaPlaylist,
            segmentLengths = segmentLengths,
            adamId = adamId,
            mode = mode,
            client = client,
            decryptClient = decryptClient,
            alacRepairParams = alacRepairParams,
            startOffset = start.coerceAtLeast(0L),
            prefetchWindow = if (highWorkerMode) HIGH_PREFETCH_WINDOW_SEGMENTS else DEFAULT_PREFETCH_WINDOW_SEGMENTS,
            startupPrefetchWindow = if (highWorkerMode) {
                HIGH_STARTUP_PREFETCH_WINDOW_SEGMENTS
            } else {
                DEFAULT_STARTUP_PREFETCH_WINDOW_SEGMENTS
            },
            prefetchConcurrency = if (highWorkerMode) HIGH_PREFETCH_CONCURRENCY else DEFAULT_PREFETCH_CONCURRENCY,
            startupReadySegments = if (highWorkerMode) HIGH_STARTUP_READY_SEGMENTS else DEFAULT_STARTUP_READY_SEGMENTS,
            trace = trace,
        )

        val remainingLength = totalLength?.let { (it - start).coerceAtLeast(0L) }
        val openLength = when {
            requestedLength >= 0L && remainingLength != null -> min(requestedLength, remainingLength)
            requestedLength >= 0L -> requestedLength
            remainingLength != null -> remainingLength
            else -> -1L
        }

        val openedStream = if (requestedLength >= 0L && openLength >= 0L) {
            BoundedInputStream(stream, openLength)
        } else {
            stream
        }
        return openedStream to openLength
    }

    fun readAlacQualityMetadata(
        client: OkHttpClient,
        initialUrl: String,
        preferFast: Boolean = false,
    ): AlacQualityMetadata? {
        return readAlacQualityInfo(client, initialUrl, preferFast)?.toMetadata()
    }

    fun describeAlacQuality(client: OkHttpClient, initialUrl: String): String? {
        return readAlacQualityMetadata(client, initialUrl)?.toLabel()
    }

    private fun readAlacQualityInfo(
        client: OkHttpClient,
        initialUrl: String,
        preferFast: Boolean,
    ): AlacQualityInfo? {
        var currentUrl = initialUrl
        var currentText = downloadText(client, currentUrl)
        var qualityInfo: AlacQualityInfo? = null

        repeat(4) {
            if (!currentText.isMasterPlaylist()) {
                val mediaQuality = runCatching {
                    val mediaPlaylist = parseMediaPlaylist(
                        currentUrl,
                        currentText,
                        AppleMusicWrapperManagerProvider.WrapperMode.ALAC,
                        qualityInfo,
                    )
                    val initQuality = runCatching {
                        readInitSegmentQuality(
                            downloadBytes(client, mediaPlaylist.init.url, mediaPlaylist.init.range)
                        )
                    }.getOrNull()
                    mediaPlaylist.qualityInfo.mergedWith(initQuality)
                }.getOrNull()
                return qualityInfo.mergedWith(mediaQuality)
            }
            val child = selectAppleMusicChildPlaylist(
                currentUrl,
                currentText,
                AppleMusicWrapperManagerProvider.WrapperMode.ALAC,
            )
                ?: return qualityInfo
            qualityInfo = qualityInfo.mergedWith(child.qualityInfo)
            if (preferFast && qualityInfo?.sampleRate != null) {
                return qualityInfo
            }
            currentUrl = child.url
            currentText = downloadText(client, currentUrl)
        }
        return qualityInfo
    }

    private class AlacTrace(
        private val adamId: String,
        private val startOffset: Long,
    ) {
        private val startedAtMs = System.currentTimeMillis()
        private val events = ConcurrentHashMap<String, Long>()

        fun mark(stage: String, value: Any? = null) {
            val elapsed = elapsedMs()
            events[stage] = elapsed
            val suffix = value?.let { " value=$it" }.orEmpty()
            Timber.tag(TRACE_TAG).d("adamId=$adamId start=$startOffset stage=$stage elapsed=${elapsed}ms$suffix")
        }

        fun <T> measure(stage: String, block: () -> T): T {
            mark("${stage}_start")
            return try {
                block().also { mark("${stage}_done") }
            } catch (error: Throwable) {
                mark("${stage}_failed", error.message ?: error.javaClass.simpleName)
                throw error
            }
        }

        fun elapsedMs(): Long = System.currentTimeMillis() - startedAtMs

        fun summary(): String {
            return events.entries
                .sortedBy { it.value }
                .joinToString(", ") { "${it.key}=${it.value}ms" }
        }
    }

    private class AppleMusicAlacStreamSession(
        private val initBytes: ByteArray,
        private val playlist: MediaPlaylist,
        private val segmentLengths: List<Long>?,
        private val adamId: String,
        private val mode: AppleMusicWrapperManagerProvider.WrapperMode,
        private val client: OkHttpClient,
        private val decryptClient: AppleMusicWrapperManagerProvider.SampleDecryptClient,
        private val alacRepairParams: AlacRepairParams?,
        private val startOffset: Long,
        private val prefetchWindow: Int,
        private val startupPrefetchWindow: Int,
        prefetchConcurrency: Int,
        private val startupReadySegments: Int,
        private val trace: AlacTrace,
    ) : InputStream() {
        private var current: InputStream? = null
        private var segmentIndex = 0
        private var nextSampleIndex = 0
        private var hasOpenedMediaSegment = false
        private var sawFirstRead = false
        private var returnedFirstDecryptedBytes = false
        private var loggedSlowStartup = false
        @Volatile
        private var closed = false
        private val prefetchExecutor = Executors.newFixedThreadPool(prefetchConcurrency.coerceAtLeast(1)) { runnable ->
            Thread(runnable, "AppleWrapperAlacPrefetch").apply { isDaemon = true }
        }
        private val prefetched = ConcurrentHashMap<Int, CompletableFuture<DecryptedSegment>>()
        private val encryptedSegments = ConcurrentHashMap<Int, CompletableFuture<EncryptedSegment>>()
        private val firstSampleIndexCache = ConcurrentHashMap<Int, Int>()
        private val firstSampleIndexFutures = ConcurrentHashMap<Int, CompletableFuture<Int>>()

        init {
            firstSampleIndexCache[0] = 0
            firstSampleIndexFutures[0] = CompletableFuture.completedFuture(0)
            openAtByteOffset(startOffset)
            schedulePrefetch()
            warmStartupBuffer()
        }

        override fun read(): Int {
            val one = ByteArray(1)
            val read = read(one, 0, 1)
            return if (read == -1) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!sawFirstRead) {
                sawFirstRead = true
                trace.mark("first_exo_read")
            }
            while (true) {
                ensureOpen()
                val active = current ?: return -1
                val read = active.read(buffer, off, len)
                if (read != -1) {
                    if (hasOpenedMediaSegment && !returnedFirstDecryptedBytes) {
                        returnedFirstDecryptedBytes = true
                        trace.mark("first_decrypted_bytes_returned", read)
                    }
                    return read
                }
                active.close()
                current = openNextSegmentStream() ?: return -1
            }
        }

        override fun close() {
            closed = true
            current?.close()
            current = null
            prefetched.values.forEach { it.cancel(true) }
            prefetched.clear()
            encryptedSegments.values.forEach { it.cancel(true) }
            encryptedSegments.clear()
            decryptClient.close()
            prefetchExecutor.shutdownNow()
        }

        private fun openNextSegmentStream(): InputStream? {
            ensureOpen()
            if (segmentIndex >= playlist.segments.size) return null
            schedulePrefetch()
            val targetIndex = segmentIndex
            val future = prefetched.remove(targetIndex)
                ?: ensurePrefetch(targetIndex).also { prefetched.remove(targetIndex) }
            val decrypted = awaitPrefetchedSegment(targetIndex, future)
            segmentIndex++
            hasOpenedMediaSegment = true
            nextSampleIndex = decrypted.firstSampleIndex + decrypted.sampleCount
            firstSampleIndexCache[segmentIndex] = nextSampleIndex
            firstSampleIndexFutures[segmentIndex] = CompletableFuture.completedFuture(nextSampleIndex)
            schedulePrefetch()
            return ByteArrayInputStream(decrypted.bytes)
        }

        private fun openAtByteOffset(offset: Long) {
            if (offset <= 0L) {
                current = ByteArrayInputStream(initBytes)
                segmentIndex = 0
                nextSampleIndex = 0
                hasOpenedMediaSegment = false
                firstSampleIndexCache[0] = 0
                firstSampleIndexFutures[0] = CompletableFuture.completedFuture(0)
                return
            }

            val lengths = segmentLengths
            if (lengths == null) {
                throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                    "ALAC seek requested but segment lengths are unavailable"
                )
            }

            if (offset < initBytes.size) {
                val initOffset = offset.toInt()
                current = ByteArrayInputStream(initBytes, initOffset, initBytes.size - initOffset)
                segmentIndex = 0
                nextSampleIndex = 0
                hasOpenedMediaSegment = false
                firstSampleIndexCache[0] = 0
                firstSampleIndexFutures[0] = CompletableFuture.completedFuture(0)
                return
            }

            var remaining = offset - initBytes.size
            var index = 0
            var firstSample = 0
            while (index < lengths.size && remaining >= lengths[index]) {
                firstSample += getSegmentSampleCount(client, playlist.segments[index])
                remaining -= lengths[index]
                index++
            }

            segmentIndex = index
            nextSampleIndex = firstSample
            firstSampleIndexCache[index] = firstSample
            firstSampleIndexFutures[index] = CompletableFuture.completedFuture(firstSample)
            if (segmentIndex >= playlist.segments.size) {
                current = null
                return
            }

            val decrypted = decryptSegment(segmentIndex, nextSampleIndex)
            segmentIndex++
            hasOpenedMediaSegment = true
            nextSampleIndex = decrypted.firstSampleIndex + decrypted.sampleCount
            firstSampleIndexCache[segmentIndex] = nextSampleIndex
            firstSampleIndexFutures[segmentIndex] = CompletableFuture.completedFuture(nextSampleIndex)
            val segmentOffset = remaining.coerceIn(0L, decrypted.bytes.size.toLong()).toInt()
            current = ByteArrayInputStream(decrypted.bytes, segmentOffset, decrypted.bytes.size - segmentOffset)
        }

        @Synchronized
        private fun schedulePrefetch() {
            if (segmentIndex >= playlist.segments.size || prefetchExecutor.isShutdown) return
            val window = if (!hasOpenedMediaSegment && segmentIndex == 0) {
                startupPrefetchWindow
            } else {
                prefetchWindow
            }.coerceAtLeast(1)
            var index = segmentIndex
            var scheduled = 0
            while (index < playlist.segments.size && scheduled < window) {
                ensurePrefetch(index)
                index++
                scheduled++
            }
            trimRollingCache()
        }

        private fun warmStartupBuffer() {
            if (startupReadySegments <= 0) return
            val lastIndex = min(playlist.segments.size, segmentIndex + startupReadySegments)
            for (index in segmentIndex until lastIndex) {
                prefetched[index]?.let { awaitPrefetchedSegment(index, it) }
            }
        }

        private fun decryptSegment(index: Int, firstSample: Int): DecryptedSegment {
            ensureOpen()
            val encrypted = loadEncryptedSegment(index)
            return decryptLoadedSegmentBytesWithRetry(
                client = client,
                decryptClient = decryptClient,
                adamId = adamId,
                playlist = playlist,
                segment = playlist.segments[index],
                encryptedBytes = encrypted.bytes,
                samples = encrypted.samples,
                segmentIndex = index,
                firstSampleIndex = firstSample,
                mode = mode,
                alacRepairParams = alacRepairParams,
                trace = trace,
            )
        }

        private fun ensurePrefetch(index: Int): CompletableFuture<DecryptedSegment> {
            return prefetched.computeIfAbsent(index) { targetIndex ->
                firstSampleIndexFuture(targetIndex).thenCombineAsync(
                    ensureEncryptedSegment(targetIndex),
                    { firstSample, encrypted ->
                        ensureOpen()
                        decryptLoadedSegmentBytesWithRetry(
                            client = client,
                            decryptClient = decryptClient,
                            adamId = adamId,
                            playlist = playlist,
                            segment = playlist.segments[targetIndex],
                            encryptedBytes = encrypted.bytes,
                            samples = encrypted.samples,
                            segmentIndex = targetIndex,
                            firstSampleIndex = firstSample,
                            mode = mode,
                            alacRepairParams = alacRepairParams,
                            trace = trace,
                        )
                    },
                    prefetchExecutor,
                ).whenComplete { _, _ ->
                    encryptedSegments.remove(targetIndex)
                    synchronized(this) {
                        trimRollingCache()
                    }
                }
            }
        }

        private fun firstSampleIndexFuture(index: Int): CompletableFuture<Int> {
            if (index <= 0) return firstSampleIndexFutures[0] ?: CompletableFuture.completedFuture(0)
            firstSampleIndexCache[index]?.let { return CompletableFuture.completedFuture(it) }
            return firstSampleIndexFutures.computeIfAbsent(index) {
                ensureEncryptedSegment(index).thenCompose { currentEncrypted ->
                    currentEncrypted.estimatedFirstSampleIndex?.let { estimated ->
                        CompletableFuture.completedFuture(estimated)
                    } ?: firstSampleIndexFuture(index - 1).thenCombineAsync(
                        ensureEncryptedSegment(index - 1),
                        { previousFirstSample, previousEncrypted ->
                            previousFirstSample + previousEncrypted.sampleCount
                        },
                        prefetchExecutor,
                    )
                }.whenComplete { value, error ->
                    if (error == null && value != null) {
                        firstSampleIndexCache[index] = value
                    }
                }
            }
        }

        private fun ensureEncryptedSegment(index: Int): CompletableFuture<EncryptedSegment> {
            return encryptedSegments.computeIfAbsent(index) { targetIndex ->
                CompletableFuture.supplyAsync(
                    {
                        ensureOpen()
                        loadEncryptedSegment(targetIndex)
                    },
                    prefetchExecutor,
                )
            }
        }

        private fun loadEncryptedSegment(index: Int): EncryptedSegment {
            ensureOpen()
            val segment = playlist.segments[index]
            val encryptedBytes = trace.measure("segment_${index}_download") {
                getEncryptedSegmentBytes(client, segment, consumeCached = true)
            }
            val samples = trace.measure("segment_${index}_sample_parse") {
                parseFragmentSamples(encryptedBytes)
            }
            segmentSampleCountCache[segmentCacheKey(segment)] = samples.size
            return EncryptedSegment(
                bytes = encryptedBytes,
                samples = samples,
                estimatedFirstSampleIndex = estimateFirstSampleIndexFromFragment(encryptedBytes, alacRepairParams),
            )
        }

        private fun awaitPrefetchedSegment(
            index: Int,
            future: CompletableFuture<DecryptedSegment>?,
        ): DecryptedSegment {
            if (future == null) {
                throw AppleMusicWrapperManagerProvider.WrapperManagerException("ALAC prefetch was not scheduled")
            }
            if (!hasOpenedMediaSegment && index == 0) {
                return awaitStartupSegment(future)
            }
            if (!future.isDone) {
                try {
                    return future.get(SEGMENT_SLOW_MS, TimeUnit.MILLISECONDS)
                } catch (error: Exception) {
                    handleSlowWaitFailure(
                        error = error,
                        index = index,
                        context = "segment",
                    )
                }
            }
            return try {
                future.get((SEGMENT_TIMEOUT_MS - SEGMENT_SLOW_MS).coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            } catch (error: Exception) {
                handleFinalWaitFailure(
                    error = error,
                    timeout = {
                        AlacSegmentTimeoutException(
                            "ALAC segment timed out after ${SEGMENT_TIMEOUT_MS}ms at segment $index; ${trace.summary()}"
                        )
                    },
                    interruptedMessage = "ALAC prefetch was interrupted",
                    unexpectedMessage = "ALAC segment prefetch wait failed",
                )
            }
        }

        private fun awaitStartupSegment(future: CompletableFuture<DecryptedSegment>): DecryptedSegment {
            val slowRemainingMs = (STARTUP_SLOW_MS - trace.elapsedMs()).coerceAtLeast(0L)
            if (slowRemainingMs > 0L && !future.isDone) {
                try {
                    return future.get(slowRemainingMs, TimeUnit.MILLISECONDS)
                } catch (error: Exception) {
                    handleSlowWaitFailure(
                        error = error,
                        index = 0,
                        context = "startup",
                    )
                }
            } else if (!future.isDone) {
                logSlowStartup()
            }

            val timeoutRemainingMs = (STARTUP_TIMEOUT_MS - trace.elapsedMs()).coerceAtLeast(1L)
            return try {
                future.get(timeoutRemainingMs, TimeUnit.MILLISECONDS)
            } catch (error: Exception) {
                handleFinalWaitFailure(
                    error = error,
                    timeout = {
                        AlacStartupTimeoutException(
                            "ALAC startup timed out after ${trace.elapsedMs()}ms; ${trace.summary()}"
                        )
                    },
                    interruptedMessage = "ALAC startup was interrupted",
                    unexpectedMessage = "ALAC startup prefetch wait failed",
                )
            }
        }

        private fun handleSlowWaitFailure(
            error: Exception,
            index: Int,
            context: String,
        ) {
            when (error) {
                is TimeoutException -> {
                    if (context == "startup") {
                        logSlowStartup()
                    } else {
                        trace.mark("segment_${index}_slow_waiting", trace.summary())
                        Timber.tag(TRACE_TAG).w(
                            "ALAC segment $index is still decrypting after ${SEGMENT_SLOW_MS}ms for adamId=$adamId"
                        )
                    }
                }

                is InterruptedException -> {
                    Thread.currentThread().interrupt()
                    throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                        "ALAC $context prefetch was interrupted",
                        error,
                    )
                }

                is ExecutionException -> throw unwrapPrefetchFailure(error)

                else -> {
                    Timber.tag(TRACE_TAG).e(error, "Unexpected ALAC $context prefetch wait failure for adamId=$adamId")
                    throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                        "ALAC $context prefetch wait failed: ${error.message ?: error.javaClass.simpleName}",
                        error,
                    )
                }
            }
        }

        private fun handleFinalWaitFailure(
            error: Exception,
            timeout: () -> Throwable,
            interruptedMessage: String,
            unexpectedMessage: String,
        ): Nothing {
            when (error) {
                is TimeoutException -> {
                    close()
                    throw timeout()
                }

                is InterruptedException -> {
                    Thread.currentThread().interrupt()
                    throw AppleMusicWrapperManagerProvider.WrapperManagerException(interruptedMessage, error)
                }

                is ExecutionException -> throw unwrapPrefetchFailure(error)

                else -> {
                    close()
                    Timber.tag(TRACE_TAG).e(error, "$unexpectedMessage for adamId=$adamId")
                    throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                        "$unexpectedMessage: ${error.message ?: error.javaClass.simpleName}",
                        error,
                    )
                }
            }
        }

        private fun logSlowStartup() {
            if (loggedSlowStartup) return
            loggedSlowStartup = true
            trace.mark("startup_slow_waiting_first_segment", trace.summary())
            Timber.tag(TRACE_TAG).w("ALAC startup is still waiting after ${trace.elapsedMs()}ms for adamId=$adamId")
        }

        private fun unwrapPrefetchFailure(error: ExecutionException): Throwable {
            val cause = error.cause
            Timber.tag(TRACE_TAG).e(cause ?: error, "ALAC prefetch failed for adamId=$adamId; ${trace.summary()}")
            if (cause is AppleMusicWrapperManagerProvider.WrapperManagerException) return cause
            if (cause is RuntimeException) return cause
            if (cause is AlacStartupTimeoutException) return cause
            if (cause is IOException) return AppleMusicWrapperManagerProvider.WrapperManagerException(
                "ALAC prefetch failed: ${cause.message ?: cause.javaClass.simpleName}",
                cause,
            )
            return AppleMusicWrapperManagerProvider.WrapperManagerException(
                "ALAC prefetch failed: ${cause?.message ?: error.message ?: "unknown error"}",
                cause ?: error,
            )
        }

        private fun trimRollingCache() {
            var completedBytes = prefetchedCompletedBytes()
            if (completedBytes <= MAX_ROLLING_CACHE_BYTES) return
            prefetched.keys
                .filter { it > segmentIndex }
                .sortedDescending()
                .forEach { index ->
                    if (completedBytes <= MAX_ROLLING_CACHE_BYTES) return
                    val future = prefetched[index] ?: return@forEach
                    val segment = completedSegmentOrNull(future) ?: return@forEach
                    prefetched.remove(index)
                    completedBytes -= segment.bytes.size.toLong()
                    trace.mark("rolling_cache_dropped", "segment=$index")
                }
            encryptedSegments.keys
                .filter { it > segmentIndex }
                .sortedDescending()
                .forEach { index ->
                    if (completedBytes <= MAX_ROLLING_CACHE_BYTES) return
                    val future = encryptedSegments[index] ?: return@forEach
                    val segment = completedEncryptedSegmentOrNull(future) ?: return@forEach
                    encryptedSegments.remove(index)
                    completedBytes -= segment.bytes.size.toLong()
                    trace.mark("rolling_encrypted_cache_dropped", "segment=$index")
                }
        }

        private fun prefetchedCompletedBytes(): Long {
            val decryptedBytes = prefetched.values.sumOf { future ->
                completedSegmentOrNull(future)?.bytes?.size?.toLong() ?: 0L
            }
            val encryptedBytes = encryptedSegments.values.sumOf { future ->
                completedEncryptedSegmentOrNull(future)?.bytes?.size?.toLong() ?: 0L
            }
            return decryptedBytes + encryptedBytes
        }

        private fun completedSegmentOrNull(future: CompletableFuture<DecryptedSegment>): DecryptedSegment? {
            return try {
                future.takeIf { it.isDone && !it.isCompletedExceptionally && !it.isCancelled }?.getNow(null)
            } catch (error: CompletionException) {
                Timber.tag(TRACE_TAG).w(error, "Completed ALAC segment lookup failed for adamId=$adamId")
                null
            }
        }

        private fun completedEncryptedSegmentOrNull(future: CompletableFuture<EncryptedSegment>): EncryptedSegment? {
            return try {
                future.takeIf { it.isDone && !it.isCompletedExceptionally && !it.isCancelled }?.getNow(null)
            } catch (error: CompletionException) {
                Timber.tag(TRACE_TAG).w(error, "Completed encrypted ALAC segment lookup failed for adamId=$adamId")
                null
            }
        }

        private fun ensureOpen() {
            if (closed) throw IOException("ALAC stream was closed")
        }
    }

    private class AppleMusicAlacVirtualHlsSession(
        private val request: VirtualHlsRequest,
        private val client: OkHttpClient,
        private val prefetchWindow: Int,
        prefetchConcurrency: Int,
        private val resourceUri: (String) -> String,
    ) : AutoCloseable {
        private val trace = AlacTrace(adamId = request.adamId, startOffset = 0L)
        private val prefetchExecutor = Executors.newFixedThreadPool(prefetchConcurrency.coerceAtLeast(1)) { runnable ->
            Thread(runnable, "AppleWrapperAlacVirtualHls").apply { isDaemon = true }
        }
        private val prefetched = ConcurrentHashMap<Int, CompletableFuture<DecryptedSegment>>()
        private val encryptedSegments = ConcurrentHashMap<Int, CompletableFuture<EncryptedSegment>>()
        private val firstSampleIndexCache = ConcurrentHashMap<Int, Int>()
        private val firstSampleIndexFutures = ConcurrentHashMap<Int, CompletableFuture<Int>>()

        private val mediaDocument = trace.measure("virtual_media_playlist_fetch") {
            resolveToMediaPlaylist(client, request.m3u8Url, request.mode)
        }
        private val playlist = parseMediaPlaylist(mediaDocument.url, mediaDocument.text, request.mode, mediaDocument.qualityInfo)
        private val rawInitBytes = trace.measure("virtual_init_fetch") {
            downloadBytes(client, playlist.init.url, playlist.init.range)
        }
        private val initQuality = readInitSegmentQuality(rawInitBytes)
            ?.also { trace.mark("virtual_format_known", it.toTraceValue()) }
        private val realQuality = playlist.qualityInfo.mergedWith(initQuality)
        private val realAverageBitrate =
            realQuality
                ?.bandwidth
                ?.takeIf { isPlausibleAlacBandwidth(it, realQuality.sampleRate) }
        private val alacRepairParams =
            if (request.mode == AppleMusicWrapperManagerProvider.WrapperMode.ALAC) {
                readAlacRepairParams(rawInitBytes)
                    ?.also { trace.mark("virtual_alac_repair_ready", "sampleSize=${it.sampleSize} channels=${it.channels}") }
            } else {
                null
            }
        private val initBytes = patchInitSegment(
            initBytes = rawInitBytes,
            mode = request.mode,
            durationMs = request.durationMs?.takeIf { it > 0L } ?: playlist.durationMs,
            averageBitrate = realAverageBitrate,
        )
        private val decryptClient = AppleMusicWrapperManagerProvider.openSampleDecryptClient(
            host = request.host,
            secure = request.secure,
            mode = request.mode,
        )
        @Volatile
        var lastAccessMs: Long = System.currentTimeMillis()
            private set

        val mediaId: String get() = request.mediaId

        @Volatile
        private var closed = false

        @Volatile
        private var firstSegmentReturned = false

        @Volatile
        private var lastRequestedSegment = 0

        val isClosed: Boolean get() = closed

        init {
            trace.mark("virtual_session_ready", "mediaId=${request.mediaId} segments=${playlist.segments.size}")
            firstSampleIndexCache[0] = 0
            firstSampleIndexFutures[0] = CompletableFuture.completedFuture(0)
            schedulePrefetch(0)
        }

        fun readResource(resourceName: String): ByteArray {
            ensureOpen()
            lastAccessMs = System.currentTimeMillis()
            return when {
                resourceName == VIRTUAL_HLS_MASTER_RESOURCE -> masterPlaylistBytes()
                resourceName == VIRTUAL_HLS_MEDIA_RESOURCE -> mediaPlaylistBytes()
                resourceName == VIRTUAL_HLS_INIT_RESOURCE -> initBytes
                resourceName.startsWith(VIRTUAL_HLS_SEGMENT_PREFIX) &&
                    resourceName.endsWith(VIRTUAL_HLS_SEGMENT_SUFFIX) -> {
                    val index = resourceName
                        .removePrefix(VIRTUAL_HLS_SEGMENT_PREFIX)
                        .removeSuffix(VIRTUAL_HLS_SEGMENT_SUFFIX)
                        .toIntOrNull()
                        ?: throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                            "Invalid ALAC virtual HLS segment resource $resourceName"
                        )
                    readSegment(index)
                }
                else -> throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                    "Unknown ALAC virtual HLS resource $resourceName"
                )
            }
        }

        private fun masterPlaylistBytes(): ByteArray {
            val bandwidth = realAverageBitrate ?: LEGACY_ALAC_PLACEHOLDER_BANDWIDTH
            return buildString {
                appendLine("#EXTM3U")
                appendLine("#EXT-X-VERSION:7")
                appendLine("#EXT-X-INDEPENDENT-SEGMENTS")
                appendLine("#EXT-X-STREAM-INF:BANDWIDTH=$bandwidth,CODECS=\"${request.mode.hlsCodecs}\"")
                appendLine(resourceUri(VIRTUAL_HLS_MEDIA_RESOURCE))
            }.toByteArray(Charsets.UTF_8)
        }

        private fun mediaPlaylistBytes(): ByteArray {
            val targetDurationSeconds = playlist.segments
                .mapNotNull { it.durationMs }
                .maxOrNull()
                ?.let { ((it + 999L) / 1000L).coerceAtLeast(1L) }
                ?: 10L
            return buildString {
                appendLine("#EXTM3U")
                appendLine("#EXT-X-VERSION:7")
                appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
                appendLine("#EXT-X-TARGETDURATION:$targetDurationSeconds")
                appendLine("#EXT-X-MEDIA-SEQUENCE:0")
                appendLine("#EXT-X-MAP:URI=\"${resourceUri(VIRTUAL_HLS_INIT_RESOURCE)}\"")
                playlist.segments.forEachIndexed { index, segment ->
                    val durationSeconds = (segment.durationMs ?: 10_000L) / 1000.0
                    appendLine(String.format(Locale.US, "#EXTINF:%.3f,", durationSeconds))
                    appendLine(resourceUri("$VIRTUAL_HLS_SEGMENT_PREFIX$index$VIRTUAL_HLS_SEGMENT_SUFFIX"))
                }
                appendLine("#EXT-X-ENDLIST")
            }.toByteArray(Charsets.UTF_8)
        }

        private fun readSegment(index: Int): ByteArray {
            ensureOpen()
            if (index !in playlist.segments.indices) {
                throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                    "ALAC virtual HLS segment $index was outside playlist bounds"
                )
            }
            lastRequestedSegment = max(lastRequestedSegment, index)
            schedulePrefetch(index)
            val future = prefetched.remove(index)
                ?: ensurePrefetch(index).also { prefetched.remove(index) }
            val segment = awaitSegment(index, future)
            firstSegmentReturned = true
            firstSampleIndexCache[index + 1] = segment.firstSampleIndex + segment.sampleCount
            firstSampleIndexFutures[index + 1] = CompletableFuture.completedFuture(
                segment.firstSampleIndex + segment.sampleCount
            )
            schedulePrefetch(index + 1)
            return segment.bytes
        }

        @Synchronized
        private fun schedulePrefetch(startIndex: Int) {
            if (prefetchExecutor.isShutdown || startIndex >= playlist.segments.size) return
            var index = startIndex.coerceAtLeast(0)
            var scheduled = 0
            val window = prefetchWindow.coerceAtLeast(1)
            while (index < playlist.segments.size && scheduled < window) {
                ensurePrefetch(index)
                index++
                scheduled++
            }
            trimRollingCache()
        }

        private fun ensurePrefetch(index: Int): CompletableFuture<DecryptedSegment> {
            return prefetched.computeIfAbsent(index) { targetIndex ->
                firstSampleIndexFuture(targetIndex).thenCombineAsync(
                    ensureEncryptedSegment(targetIndex),
                    { firstSample, encrypted ->
                        ensureOpen()
                        decryptLoadedSegmentBytesWithRetry(
                            client = client,
                            decryptClient = decryptClient,
                            adamId = request.adamId,
                            playlist = playlist,
                            segment = playlist.segments[targetIndex],
                            encryptedBytes = encrypted.bytes,
                            samples = encrypted.samples,
                            segmentIndex = targetIndex,
                            firstSampleIndex = firstSample,
                            mode = request.mode,
                            alacRepairParams = alacRepairParams,
                            trace = trace,
                        )
                    },
                    prefetchExecutor,
                ).whenComplete { _, _ ->
                    encryptedSegments.remove(targetIndex)
                    synchronized(this) {
                        trimRollingCache()
                    }
                }
            }
        }

        private fun firstSampleIndexFuture(index: Int): CompletableFuture<Int> {
            if (index <= 0) return firstSampleIndexFutures[0] ?: CompletableFuture.completedFuture(0)
            firstSampleIndexCache[index]?.let { return CompletableFuture.completedFuture(it) }
            return firstSampleIndexFutures.computeIfAbsent(index) {
                ensureEncryptedSegment(index).thenCompose { currentEncrypted ->
                    currentEncrypted.estimatedFirstSampleIndex?.let { estimated ->
                        CompletableFuture.completedFuture(estimated)
                    } ?: firstSampleIndexFuture(index - 1).thenCombineAsync(
                        ensureEncryptedSegment(index - 1),
                        { previousFirstSample, previousEncrypted ->
                            previousFirstSample + previousEncrypted.sampleCount
                        },
                        prefetchExecutor,
                    )
                }.whenComplete { value, error ->
                    if (error == null && value != null) {
                        firstSampleIndexCache[index] = value
                    }
                }
            }
        }

        private fun ensureEncryptedSegment(index: Int): CompletableFuture<EncryptedSegment> {
            return encryptedSegments.computeIfAbsent(index) { targetIndex ->
                CompletableFuture.supplyAsync(
                    {
                        ensureOpen()
                        loadEncryptedSegment(targetIndex)
                    },
                    prefetchExecutor,
                )
            }
        }

        private fun loadEncryptedSegment(index: Int): EncryptedSegment {
            ensureOpen()
            val segment = playlist.segments[index]
            val encryptedBytes = trace.measure("virtual_segment_${index}_download") {
                getEncryptedSegmentBytes(client, segment, consumeCached = true)
            }
            val samples = trace.measure("virtual_segment_${index}_sample_parse") {
                parseFragmentSamples(encryptedBytes)
            }
            segmentSampleCountCache[segmentCacheKey(segment)] = samples.size
            return EncryptedSegment(
                bytes = encryptedBytes,
                samples = samples,
                estimatedFirstSampleIndex = estimateFirstSampleIndexFromFragment(encryptedBytes, alacRepairParams),
            )
        }

        private fun awaitSegment(
            index: Int,
            future: CompletableFuture<DecryptedSegment>,
        ): DecryptedSegment {
            val slowTimeoutMs = if (!firstSegmentReturned && index == 0) {
                (STARTUP_SLOW_MS - trace.elapsedMs()).coerceAtLeast(1L)
            } else {
                SEGMENT_SLOW_MS
            }
            if (!future.isDone) {
                try {
                    return future.get(slowTimeoutMs, TimeUnit.MILLISECONDS)
                } catch (error: TimeoutException) {
                    trace.mark("virtual_segment_${index}_slow_waiting", trace.summary())
                    Timber.tag(TRACE_TAG).w(
                        "ALAC virtual HLS segment $index is still decrypting after ${slowTimeoutMs}ms for adamId=${request.adamId}"
                    )
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                        "ALAC virtual HLS segment wait was interrupted",
                        error,
                    )
                } catch (error: ExecutionException) {
                    throw unwrapPrefetchFailure(error)
                }
            }

            val finalTimeoutMs = if (!firstSegmentReturned && index == 0) {
                (STARTUP_TIMEOUT_MS - trace.elapsedMs()).coerceAtLeast(1L)
            } else {
                (SEGMENT_TIMEOUT_MS - SEGMENT_SLOW_MS).coerceAtLeast(1L)
            }
            return try {
                future.get(finalTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (error: TimeoutException) {
                if (!firstSegmentReturned && index == 0) {
                    close()
                    throw AlacStartupTimeoutException(
                        "ALAC virtual HLS startup timed out after ${trace.elapsedMs()}ms; ${trace.summary()}"
                    )
                }
                throw AlacSegmentTimeoutException(
                    "ALAC virtual HLS segment timed out after ${SEGMENT_TIMEOUT_MS}ms at segment $index; ${trace.summary()}"
                )
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                    "ALAC virtual HLS segment wait was interrupted",
                    error,
                )
            } catch (error: ExecutionException) {
                throw unwrapPrefetchFailure(error)
            }
        }

        private fun unwrapPrefetchFailure(error: ExecutionException): Throwable {
            val cause = error.cause
            Timber.tag(TRACE_TAG).e(cause ?: error, "ALAC virtual HLS prefetch failed for adamId=${request.adamId}; ${trace.summary()}")
            if (cause is AppleMusicWrapperManagerProvider.WrapperManagerException) return cause
            if (cause is RuntimeException) return cause
            if (cause is AlacStartupTimeoutException) return cause
            if (cause is IOException) return AppleMusicWrapperManagerProvider.WrapperManagerException(
                "ALAC virtual HLS prefetch failed: ${cause.message ?: cause.javaClass.simpleName}",
                cause,
            )
            return AppleMusicWrapperManagerProvider.WrapperManagerException(
                "ALAC virtual HLS prefetch failed: ${cause?.message ?: error.message ?: "unknown error"}",
                cause ?: error,
            )
        }

        private fun trimRollingCache() {
            var completedBytes = prefetchedCompletedBytes()
            if (completedBytes <= MAX_ROLLING_CACHE_BYTES) return
            prefetched.keys
                .filter { it > lastRequestedSegment }
                .sortedDescending()
                .forEach { index ->
                    if (completedBytes <= MAX_ROLLING_CACHE_BYTES) return
                    val future = prefetched[index] ?: return@forEach
                    val segment = completedSegmentOrNull(future) ?: return@forEach
                    prefetched.remove(index)
                    completedBytes -= segment.bytes.size.toLong()
                    trace.mark("virtual_rolling_cache_dropped", "segment=$index")
                }
        }

        private fun prefetchedCompletedBytes(): Long {
            return prefetched.values.sumOf { future ->
                completedSegmentOrNull(future)?.bytes?.size?.toLong() ?: 0L
            } + encryptedSegments.values.sumOf { future ->
                completedEncryptedSegmentOrNull(future)?.bytes?.size?.toLong() ?: 0L
            }
        }

        private fun completedSegmentOrNull(future: CompletableFuture<DecryptedSegment>): DecryptedSegment? {
            return try {
                future.takeIf { it.isDone && !it.isCompletedExceptionally && !it.isCancelled }?.getNow(null)
            } catch (error: CompletionException) {
                Timber.tag(TRACE_TAG).w(error, "Completed ALAC virtual HLS segment lookup failed for adamId=${request.adamId}")
                null
            }
        }

        private fun completedEncryptedSegmentOrNull(future: CompletableFuture<EncryptedSegment>): EncryptedSegment? {
            return try {
                future.takeIf { it.isDone && !it.isCompletedExceptionally && !it.isCancelled }?.getNow(null)
            } catch (error: CompletionException) {
                Timber.tag(TRACE_TAG).w(error, "Completed encrypted ALAC virtual HLS segment lookup failed for adamId=${request.adamId}")
                null
            }
        }

        private fun ensureOpen() {
            if (closed) throw IOException("ALAC virtual HLS session was closed")
        }

        override fun close() {
            if (closed) return
            closed = true
            prefetched.values.forEach { it.cancel(true) }
            prefetched.clear()
            encryptedSegments.values.forEach { it.cancel(true) }
            encryptedSegments.clear()
            decryptClient.close()
            prefetchExecutor.shutdownNow()
        }
    }

    private class BoundedInputStream(
        private val upstream: InputStream,
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0L) return -1
            val value = upstream.read()
            if (value != -1) remaining--
            return value
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val capped = min(len.toLong(), remaining).toInt()
            val read = upstream.read(buffer, off, capped)
            if (read > 0) remaining -= read
            return read
        }

        override fun close() {
            upstream.close()
        }
    }

    private fun resolveToMediaPlaylist(
        client: OkHttpClient,
        initialUrl: String,
        mode: AppleMusicWrapperManagerProvider.WrapperMode = AppleMusicWrapperManagerProvider.WrapperMode.ALAC,
    ): PlaylistDocument {
        var currentUrl = initialUrl
        var currentText = downloadText(client, currentUrl)
        var qualityInfo: AlacQualityInfo? = null
        repeat(4) {
            if (!currentText.isMasterPlaylist()) {
                return PlaylistDocument(currentUrl, currentText, qualityInfo)
            }
            val child = selectAppleMusicChildPlaylist(currentUrl, currentText, mode)
                ?: throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                    "wrapper-manager M3U8 did not expose a ${mode.title} media playlist"
                )
            qualityInfo = qualityInfo.mergedWith(child.qualityInfo)
            currentUrl = child.url
            currentText = downloadText(client, currentUrl)
        }
        throw AppleMusicWrapperManagerProvider.WrapperManagerException(
            "wrapper-manager M3U8 nested too deeply"
        )
    }

    private fun String.isMasterPlaylist(): Boolean {
        return lineSequence().any {
            val line = it.trim()
            line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) ||
                line.startsWith("#EXT-X-MEDIA:", ignoreCase = true)
        }
    }

    private fun selectAppleMusicChildPlaylist(
        baseUrl: String,
        playlist: String,
        mode: AppleMusicWrapperManagerProvider.WrapperMode,
    ): PlaylistSelection? {
        val lines = playlist.lineSequence().map { it.trim() }.toList()
        val mediaByGroup = mutableMapOf<String, PlaylistSelection>()
        val matchingMediaByGroup = mutableMapOf<String, PlaylistSelection>()
        val childPlaylists = mutableListOf<PlaylistSelection>()

        lines.forEach { line ->
            if (line.startsWith("#EXT-X-MEDIA:", ignoreCase = true)) {
                val attrs = parseAttributes(line.substringAfter(':'))
                val uri = attrs["URI"]?.takeIf { it.isNotBlank() } ?: return@forEach
                val groupId = attrs["GROUP-ID"].orEmpty()
                val selection = PlaylistSelection(resolveUri(baseUrl, uri), attrs.toQualityInfo())
                if (groupId.isNotBlank()) {
                    mediaByGroup[groupId] = selection
                    if (attrs.matchesAppleMusicMode(mode)) {
                        matchingMediaByGroup[groupId] = selection
                    }
                }
            } else if (line.isNotEmpty() && !line.startsWith("#") && line.endsWith(".m3u8", ignoreCase = true)) {
                childPlaylists += PlaylistSelection(resolveUri(baseUrl, line), null)
            }
        }

        lines.forEachIndexed { index, line ->
            if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                val attrs = parseAttributes(line.substringAfter(':'))
                val streamQuality = attrs.toQualityInfo()
                val uri = nextPlaylistUri(lines, index + 1)?.let { resolveUri(baseUrl, it) }
                if (attrs.matchesAppleMusicMode(mode) && uri != null) {
                    return PlaylistSelection(uri, streamQuality)
                }
                val audioGroup = attrs["AUDIO"]
                if (audioGroup != null) {
                    matchingMediaByGroup[audioGroup]?.let { return it.withQuality(streamQuality) }
                    if (audioGroup.matchesAppleMusicMode(mode)) {
                        mediaByGroup[audioGroup]?.let { return it.withQuality(streamQuality) }
                        if (uri != null) return PlaylistSelection(uri, streamQuality)
                    }
                }
            }
        }

        matchingMediaByGroup.values.firstOrNull()?.let { return it }
        return childPlaylists.distinct().singleOrNull()
    }

    private fun Map<String, String>.matchesAppleMusicMode(
        mode: AppleMusicWrapperManagerProvider.WrapperMode,
    ): Boolean = values.any { it.matchesAppleMusicMode(mode) }

    private fun String.matchesAppleMusicMode(
        mode: AppleMusicWrapperManagerProvider.WrapperMode,
    ): Boolean {
        val value = lowercase(Locale.US)
        return when (mode) {
            AppleMusicWrapperManagerProvider.WrapperMode.ALAC ->
                value.contains("alac") || value.contains("lossless")
            AppleMusicWrapperManagerProvider.WrapperMode.AAC ->
                Regex("""audio-stereo-\d{3}($|["',\s])""").containsMatchIn(value) &&
                    !value.contains("binaural") &&
                    !value.contains("downmix")
            AppleMusicWrapperManagerProvider.WrapperMode.DOLBY_ATMOS ->
                value.contains("audio-atmos") ||
                    value.contains("audio-ec3") ||
                    value.contains("audio-ac3") ||
                    value.contains("ec-3") ||
                    value.contains("ac-3") ||
                    value.contains("atmos")
        }
    }

    private fun nextPlaylistUri(lines: List<String>, startIndex: Int): String? {
        for (i in startIndex until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            if (line.startsWith("#")) continue
            return line
        }
        return null
    }

    private fun parseMediaPlaylist(
        baseUrl: String,
        playlist: String,
        mode: AppleMusicWrapperManagerProvider.WrapperMode = AppleMusicWrapperManagerProvider.WrapperMode.ALAC,
        qualityInfo: AlacQualityInfo? = null,
    ): MediaPlaylist {
        val keyRing = mutableListOf(PREFETCH_KEY)
        val segments = mutableListOf<MediaSegment>()
        val nextOffsetByUrl = mutableMapOf<String, Long>()
        var currentKey: String? = null
        var pendingRange: ByteRangeSpec? = null
        var pendingDurationMs: Long? = null
        var totalDurationMs = 0L
        var sawExtinf = false
        var init: InitSegment? = null

        playlist.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-KEY:", ignoreCase = true) -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    currentKey = if (attrs["METHOD"].equals("NONE", ignoreCase = true)) {
                        null
                    } else {
                        attrs["URI"]?.let { uri ->
                            val resolved = resolveKeyUri(baseUrl, uri)
                            if (resolved.isAppleMusicDecryptKey(mode) && resolved !in keyRing) {
                                keyRing += resolved
                            }
                            resolved.takeIf { it.isAppleMusicDecryptKey(mode) }
                        }
                    }
                }
                line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    val attrs = parseAttributes(line.substringAfter(':'))
                    val uri = attrs["URI"]
                        ?: throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                            "ALAC media playlist had EXT-X-MAP without URI"
                        )
                    val url = resolveUri(baseUrl, uri)
                    val range = attrs["BYTERANGE"]?.let { parseByteRangeSpec(it) }
                        ?.let { resolveByteRange(url, it, nextOffsetByUrl) }
                    init = InitSegment(url, range)
                }
                line.startsWith("#EXT-X-BYTERANGE:", ignoreCase = true) -> {
                    pendingRange = parseByteRangeSpec(line.substringAfter(':'))
                }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    line.substringAfter(':')
                        .substringBefore(',')
                        .trim()
                        .toDoubleOrNull()
                        ?.let { seconds ->
                            val duration = (seconds * 1000.0).toLong().coerceAtLeast(1L)
                            pendingDurationMs = duration
                            totalDurationMs += duration
                            sawExtinf = true
                        }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val url = resolveUri(baseUrl, line)
                    val range = pendingRange?.let { resolveByteRange(url, it, nextOffsetByUrl) }
                    segments += MediaSegment(url = url, range = range, keyUri = currentKey, durationMs = pendingDurationMs)
                    pendingRange = null
                    pendingDurationMs = null
                }
            }
        }

        val map = init ?: throw AppleMusicWrapperManagerProvider.WrapperManagerException(
            "ALAC media playlist did not include EXT-X-MAP init segment"
        )
        if (segments.isEmpty()) {
            throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                "ALAC media playlist did not include media segments"
            )
        }
        return MediaPlaylist(
            init = map,
            segments = segments,
            keyRing = keyRing.distinct(),
            durationMs = totalDurationMs.takeIf { sawExtinf && it > 0L },
            qualityInfo = qualityInfo,
        )
    }

    private fun decryptSegmentBytes(
        client: OkHttpClient,
        decryptClient: AppleMusicWrapperManagerProvider.SampleDecryptClient,
        adamId: String,
        playlist: MediaPlaylist,
        segment: MediaSegment,
        segmentIndex: Int,
        firstSampleIndex: Int,
        trace: AlacTrace,
    ): DecryptedSegment {
        val encryptedBytes = trace.measure("segment_${segmentIndex}_download") {
            getEncryptedSegmentBytes(client, segment, consumeCached = true)
        }
        val samples = trace.measure("segment_${segmentIndex}_sample_parse") {
            parseFragmentSamples(encryptedBytes)
        }
        segmentSampleCountCache[segmentCacheKey(segment)] = samples.size
        return decryptLoadedSegmentBytesWithRetry(
            client = client,
            decryptClient = decryptClient,
            adamId = adamId,
            playlist = playlist,
            segment = segment,
            encryptedBytes = encryptedBytes,
            samples = samples,
            segmentIndex = segmentIndex,
            firstSampleIndex = firstSampleIndex,
            mode = AppleMusicWrapperManagerProvider.WrapperMode.ALAC,
            alacRepairParams = null,
            trace = trace,
        )
    }

    private fun decryptLoadedSegmentBytesWithRetry(
        client: OkHttpClient,
        decryptClient: AppleMusicWrapperManagerProvider.SampleDecryptClient,
        adamId: String,
        playlist: MediaPlaylist,
        segment: MediaSegment,
        encryptedBytes: ByteArray,
        samples: List<SampleRange>,
        segmentIndex: Int,
        firstSampleIndex: Int,
        mode: AppleMusicWrapperManagerProvider.WrapperMode,
        alacRepairParams: AlacRepairParams?,
        trace: AlacTrace,
    ): DecryptedSegment {
        var lastError: Throwable? = null
        val decryptContext = segmentDecryptContext(segmentIndex)
        repeat(SEGMENT_INTEGRITY_RETRY_COUNT + 1) { attempt ->
            try {
                return decryptLoadedSegmentBytes(
                    client = client,
                    decryptClient = decryptClient,
                    adamId = adamId,
                    playlist = playlist,
                    segment = segment,
                    encryptedBytes = encryptedBytes,
                    samples = samples,
                    segmentIndex = segmentIndex,
                    firstSampleIndex = firstSampleIndex,
                    mode = mode,
                    alacRepairParams = alacRepairParams,
                    decryptContext = decryptContext,
                    trace = trace,
                ).also {
                    decryptClient.clearSegmentContext(decryptContext)
                }
            } catch (error: AlacSegmentIntegrityException) {
                lastError = error
                val rerouted = decryptClient.reportSegmentIntegrityFailure(
                    context = decryptContext,
                    reason = error.message.orEmpty().take(180),
                )
                trace.mark(
                    "segment_${segmentIndex}_integrity_retry",
                    "attempt=${attempt + 1} rerouted=$rerouted error=${error.message.orEmpty().take(140)}"
                )
            } catch (error: AppleMusicWrapperManagerProvider.WrapperManagerException) {
                if (!error.isSampleIntegrityFailure()) throw error
                lastError = error
                val rerouted = decryptClient.reportSegmentIntegrityFailure(
                    context = decryptContext,
                    reason = error.message.orEmpty().take(180),
                )
                trace.mark(
                    "segment_${segmentIndex}_integrity_retry",
                    "attempt=${attempt + 1} rerouted=$rerouted error=${error.message.orEmpty().take(140)}"
                )
            }
        }
        throw AlacSegmentIntegrityException(
            "ALAC segment $segmentIndex failed integrity validation after retries",
            lastError,
        )
    }

    private fun decryptLoadedSegmentBytes(
        @Suppress("UNUSED_PARAMETER")
        client: OkHttpClient,
        decryptClient: AppleMusicWrapperManagerProvider.SampleDecryptClient,
        adamId: String,
        playlist: MediaPlaylist,
        segment: MediaSegment,
        encryptedBytes: ByteArray,
        samples: List<SampleRange>,
        segmentIndex: Int,
        firstSampleIndex: Int,
        mode: AppleMusicWrapperManagerProvider.WrapperMode,
        alacRepairParams: AlacRepairParams?,
        decryptContext: String,
        trace: AlacTrace,
    ): DecryptedSegment {
        validateEncryptedFragment(encryptedBytes, samples, segmentIndex)
        if (samples.isEmpty()) {
            return DecryptedSegment(encryptedBytes, 0, firstSampleIndex)
        }

        val output = encryptedBytes.copyOf()
        var preservedTailBytes = 0
        val requestsByKey = samples.mapIndexedNotNull { localIndex, sample ->
            val globalIndex = firstSampleIndex + localIndex
            val key = selectKeyForSample(
                keyRing = playlist.keyRing,
                segmentKey = segment.keyUri,
                sampleDescriptionIndex = sample.sampleDescriptionIndex
            )
            val encryptedSample = encryptedBytes.copyOfRange(sample.offset, sample.offset + sample.size)
            val decryptLength = if (mode == AppleMusicWrapperManagerProvider.WrapperMode.ALAC) {
                encryptedSample.cbcsDecryptLength()
            } else {
                encryptedSample.size
            }
            preservedTailBytes += encryptedSample.size - decryptLength
            if (decryptLength <= 0) return@mapIndexedNotNull null
            val encryptedPayload = if (decryptLength == encryptedSample.size) {
                encryptedSample
            } else {
                encryptedSample.copyOf(decryptLength)
            }
            SampleDecryptRequest(
                key = key,
                range = sample,
                decryptLength = decryptLength,
                request = AppleMusicWrapperManagerProvider.DecryptSample(globalIndex, encryptedPayload)
            )
        }.groupBy { it.key }
        if (preservedTailBytes > 0) {
            trace.mark("segment_${segmentIndex}_cbcs_tail_preserved", preservedTailBytes)
        }

        requestsByKey.forEach { (key, keyedRequests) ->
            val decryptSamples = keyedRequests.map { it.request }
            val decrypted = trace.measure("segment_${segmentIndex}_decrypt_${decryptSamples.size}") {
                runCatching {
                    decryptClient.decryptSegment(
                        adamId = adamId,
                        key = key,
                        samples = decryptSamples,
                        context = decryptContext,
                    )
                }.recoverCatching { error ->
                    if (error.message?.contains("did not return sample", ignoreCase = true) != true) throw error
                    Thread.sleep(500L)
                    decryptClient.decryptSegment(
                        adamId = adamId,
                        key = key,
                        samples = decryptSamples,
                        context = decryptContext,
                    )
                }.getOrThrow()
            }
            keyedRequests.forEach { sampleRequest ->
                val decryptedSample = decrypted[sampleRequest.request.sampleIndex]
                    ?: throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                        "wrapper-manager did not return decrypted sample ${sampleRequest.request.sampleIndex}"
                    )
                if (decryptedSample.size != sampleRequest.decryptLength) {
                    throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                        "wrapper-manager sample ${sampleRequest.request.sampleIndex} size mismatch"
                    )
                }
                System.arraycopy(
                    decryptedSample,
                    0,
                    output,
                    sampleRequest.range.offset,
                    decryptedSample.size
                )
            }
        }

        val repairStats = repairAlacPackets(output, samples, alacRepairParams)
        if (repairStats.repaired > 0) {
            trace.mark("segment_${segmentIndex}_alac_packets_repaired", repairStats.repaired)
        }
        if (repairStats.unparseable > 0) {
            trace.mark("segment_${segmentIndex}_alac_packets_unparseable", repairStats.unparseable)
        }
        stripFragmentEncryptionBoxes(output)
        validateDecryptedFragment(output, samples.size, segmentIndex)
        trace.mark("segment_${segmentIndex}_ready", "samples=${samples.size}")
        return DecryptedSegment(output, samples.size, firstSampleIndex)
    }

    private fun resolveSegmentLengths(client: OkHttpClient, segments: List<MediaSegment>): List<Long>? {
        val lengths = mutableListOf<Long>()
        for (segment in segments) {
            val length = segment.range?.length
                ?: getSegmentContentLength(client, segment)
                ?: return null
            lengths += length
        }
        return lengths
    }

    private fun estimateAverageBandwidth(
        playlist: MediaPlaylist,
        segmentLengths: List<Long>?,
    ): Long? {
        val lengths = segmentLengths ?: return null
        if (lengths.size != playlist.segments.size) return null
        return estimateAverageBandwidth(
            playlist.segments.mapIndexedNotNull { index, segment ->
                val duration = segment.durationMs?.takeIf { it > 0L } ?: return@mapIndexedNotNull null
                lengths[index].takeIf { it > 0L }?.let { length -> length to duration }
            }
        )
    }

    private fun estimateAverageBandwidth(
        client: OkHttpClient,
        playlist: MediaPlaylist,
        maxRemoteSegments: Int,
    ): Long? {
        val hasInlineRanges = playlist.segments.any { it.range != null }
        var remoteLookups = 0
        val measured = playlist.segments.mapNotNull { segment ->
            val duration = segment.durationMs?.takeIf { it > 0L } ?: return@mapNotNull null
            val length = segment.range?.length ?: run {
                if (!hasInlineRanges && remoteLookups >= maxRemoteSegments) return@mapNotNull null
                remoteLookups++
                getSegmentContentLength(client, segment)
            }
            length?.takeIf { it > 0L }?.let { it to duration }
        }
        return estimateAverageBandwidth(measured)
    }

    private fun estimateAverageBandwidth(measuredSegments: List<Pair<Long, Long>>): Long? {
        if (measuredSegments.isEmpty()) return null
        val totalBytes = measuredSegments.sumOf { it.first }
        val totalDurationMs = measuredSegments.sumOf { it.second }
        if (totalBytes <= 0L || totalDurationMs <= 0L) return null
        return ((totalBytes * 8L * 1000L) / totalDurationMs).takeIf { it > 0L }
    }

    private fun buildSeekableInitSegment(
        initBytes: ByteArray,
        playlist: MediaPlaylist,
        segmentLengths: List<Long>?,
    ): ByteArray {
        val lengths = segmentLengths ?: return initBytes
        if (lengths.size != playlist.segments.size) return initBytes
        if (playlist.segments.any { it.durationMs == null || it.durationMs <= 0L }) return initBytes
        val sidx = buildSidxBox(playlist.segments, lengths) ?: return initBytes
        return ByteArrayOutputStream(initBytes.size + sidx.size).use { output ->
            output.write(initBytes)
            output.write(sidx)
            output.toByteArray()
        }
    }

    private fun buildSidxBox(segments: List<MediaSegment>, segmentLengths: List<Long>): ByteArray? {
        if (segments.isEmpty() || segments.size > 0xffff) return null
        val size = 8 + 4 + 4 + 4 + 4 + 4 + 2 + 2 + segments.size * 12
        val data = ByteArray(size)
        writeUInt32(data, 0, size.toLong())
        writeAscii(data, 4, "sidx")
        var pos = 8
        writeUInt32(data, pos, 0L)
        pos += 4
        writeUInt32(data, pos, 1L)
        pos += 4
        writeUInt32(data, pos, 1000L)
        pos += 4
        writeUInt32(data, pos, 0L)
        pos += 4
        writeUInt32(data, pos, 0L)
        pos += 4
        writeUInt16(data, pos, 0)
        pos += 2
        writeUInt16(data, pos, segments.size)
        pos += 2
        segments.forEachIndexed { index, segment ->
            val length = segmentLengths[index]
            if (length <= 0L || length > 0x7fffffffL) return null
            writeUInt32(data, pos, length)
            pos += 4
            writeUInt32(data, pos, segment.durationMs ?: return null)
            pos += 4
            writeUInt32(data, pos, 0x90000000L)
            pos += 4
        }
        return data
    }

    private fun getSegmentContentLength(client: OkHttpClient, segment: MediaSegment): Long? {
        val key = segmentCacheKey(segment)
        segmentLengthCache[key]?.let { return it }
        val request = Request.Builder()
            .url(segment.url)
            .head()
            .header("User-Agent", USER_AGENT)
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.header("Content-Length")
                    ?.toLongOrNull()
                    ?.takeIf { it > 0L }
                    ?.also { segmentLengthCache[key] = it }
            }
        }.getOrNull()
    }

    private fun getSegmentSampleCount(client: OkHttpClient, segment: MediaSegment): Int {
        val key = segmentCacheKey(segment)
        segmentSampleCountCache[key]?.let { return it }
        val count = parseFragmentSamples(getEncryptedSegmentBytes(client, segment, consumeCached = false)).size
        segmentSampleCountCache[key] = count
        return count
    }

    private fun getEncryptedSegmentBytes(
        client: OkHttpClient,
        segment: MediaSegment,
        consumeCached: Boolean,
    ): ByteArray {
        val key = segmentCacheKey(segment)
        if (consumeCached) {
            encryptedSegmentCache.remove(key)?.let { return it }
        } else {
            encryptedSegmentCache[key]?.let { return it }
        }
        return downloadBytes(client, segment.url, segment.range).also { bytes ->
            if (!consumeCached && bytes.size <= MAX_ENCRYPTED_SEGMENT_CACHE_BYTES) {
                encryptedSegmentCache.clear()
                encryptedSegmentCache[key] = bytes
            }
        }
    }

    private fun segmentCacheKey(segment: MediaSegment): String {
        val range = segment.range
        return if (range == null) {
            segment.url
        } else {
            "${segment.url}#${range.offset}:${range.length}"
        }
    }

    private fun selectKeyForSample(
        keyRing: List<String>,
        segmentKey: String?,
        sampleDescriptionIndex: Int,
    ): String {
        keyRing.getOrNull(sampleDescriptionIndex)?.let { return it }
        segmentKey?.let { return it }
        return keyRing.lastOrNull()
            ?: throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                "ALAC media segment did not have a decrypt key"
            )
    }

    private fun String.isAppleMusicDecryptKey(
        mode: AppleMusicWrapperManagerProvider.WrapperMode,
    ): Boolean {
        return endsWith(mode.keySuffix) || endsWith(KEY_SUFFIX_DEFAULT)
    }

    private fun parseFragmentSamples(data: ByteArray): List<SampleRange> {
        val samples = mutableListOf<SampleRange>()
        var pos = 0
        while (pos < data.size) {
            val box = readBox(data, pos, data.size) ?: break
            if (box.type == "moof") {
                val mdat = findNextTopLevelBox(data, box.end, data.size, "mdat")
                    ?: throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                        "ALAC fMP4 segment had moof without following mdat"
                    )
                samples += parseMoofSamples(data, box, mdat)
                pos = mdat.end
            } else {
                pos = box.end
            }
        }
        return samples
    }

    private fun estimateFirstSampleIndexFromFragment(
        data: ByteArray,
        params: AlacRepairParams?,
    ): Int? {
        val maxSamplesPerFrame = params?.maxSamplesPerFrame?.takeIf { it > 0 }?.toLong() ?: return null
        var estimated: Int? = null
        forEachChildBox(data, 0, data.size) { moof ->
            if (estimated != null || moof.type != "moof") return@forEachChildBox
            forEachChildBox(data, moof.payloadStart, moof.end) { traf ->
                if (estimated != null || traf.type != "traf") return@forEachChildBox
                val tfdt = findChildBox(data, traf, "tfdt") ?: return@forEachChildBox
                val baseDecodeTime = parseTfdtBaseDecodeTime(data, tfdt) ?: return@forEachChildBox
                if (baseDecodeTime % maxSamplesPerFrame != 0L) return@forEachChildBox
                val sampleIndex = baseDecodeTime / maxSamplesPerFrame
                if (sampleIndex <= Int.MAX_VALUE) {
                    estimated = sampleIndex.toInt()
                }
            }
        }
        return estimated
    }

    private fun parseTfdtBaseDecodeTime(data: ByteArray, box: Mp4Box): Long? {
        val version = fullBoxVersion(data, box)
        val offset = box.payloadStart + 4
        return if (version == 1) {
            if (offset + 8 > box.end) null else readUInt64(data, offset)
        } else {
            if (offset + 4 > box.end) null else readUInt32(data, offset)
        }
    }

    private fun parseMoofSamples(data: ByteArray, moof: Mp4Box, mdat: Mp4Box): List<SampleRange> {
        val samples = mutableListOf<SampleRange>()
        var implicitDataOffset = mdat.payloadStart

        forEachChildBox(data, moof.payloadStart, moof.end) trafLoop@ { child ->
            if (child.type != "traf") return@trafLoop
            val tfhd = findChildBox(data, child, "tfhd")?.let { parseTfhd(data, it) } ?: TfhdInfo()
            forEachChildBox(data, child.payloadStart, child.end) trunLoop@ { trafChild ->
                if (trafChild.type != "trun") return@trunLoop
                val parsed = parseTrunSamples(
                    data = data,
                    trun = trafChild,
                    moof = moof,
                    mdat = mdat,
                    tfhd = tfhd,
                    implicitDataOffset = implicitDataOffset
                )
                samples += parsed.samples
                implicitDataOffset = max(implicitDataOffset, parsed.nextImplicitOffset)
            }
        }

        samples.forEach { sample ->
            if (sample.offset < 0 || sample.size <= 0 || sample.offset + sample.size > data.size) {
                throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                    "ALAC fMP4 sample range was invalid"
                )
            }
        }
        return samples
    }

    private fun validateEncryptedFragment(
        data: ByteArray,
        samples: List<SampleRange>,
        segmentIndex: Int,
    ) {
        validateTopLevelBoxLayout(data, "encrypted segment $segmentIndex")
        val parsedSamples = try {
            parseFragmentSamples(data)
        } catch (error: AppleMusicWrapperManagerProvider.WrapperManagerException) {
            throw AlacSegmentIntegrityException(
                "ALAC encrypted segment $segmentIndex container validation failed: ${error.message}",
                error,
            )
        }
        if (parsedSamples.size != samples.size) {
            throw AlacSegmentIntegrityException(
                "ALAC encrypted segment $segmentIndex sample count changed during validation"
            )
        }
    }

    private fun validateDecryptedFragment(
        data: ByteArray,
        expectedSamples: Int,
        segmentIndex: Int,
    ) {
        validateTopLevelBoxLayout(data, "decrypted segment $segmentIndex")
        val parsedSamples = try {
            parseFragmentSamples(data)
        } catch (error: AppleMusicWrapperManagerProvider.WrapperManagerException) {
            throw AlacSegmentIntegrityException(
                "ALAC decrypted segment $segmentIndex container validation failed: ${error.message}",
                error,
            )
        }
        if (parsedSamples.size != expectedSamples) {
            throw AlacSegmentIntegrityException(
                "ALAC decrypted segment $segmentIndex sample count mismatch expected=$expectedSamples actual=${parsedSamples.size}"
            )
        }
        if (data.hasFragmentEncryptionBoxes()) {
            throw AlacSegmentIntegrityException("ALAC decrypted segment $segmentIndex still contains encryption boxes")
        }
    }

    private fun validateTopLevelBoxLayout(data: ByteArray, label: String) {
        if (data.isEmpty()) {
            throw AlacSegmentIntegrityException("ALAC $label was empty")
        }
        var pos = 0
        var sawMoof = false
        var sawMdat = false
        while (pos < data.size) {
            val box = readRequiredBox(data, pos, data.size, label)
            if (!box.type.isPrintableMp4Type()) {
                throw AlacSegmentIntegrityException("ALAC $label had invalid atom type at offset $pos")
            }
            when (box.type) {
                "moof" -> sawMoof = true
                "mdat" -> sawMdat = true
            }
            if (box.type in containerBoxes) {
                validateChildBoxLayout(data, box.payloadStart, box.end, "$label/${box.type}")
            }
            pos = box.end
        }
        if (pos != data.size) {
            throw AlacSegmentIntegrityException("ALAC $label had trailing malformed bytes")
        }
        if (label.contains("segment") && (!sawMoof || !sawMdat)) {
            throw AlacSegmentIntegrityException("ALAC $label did not contain moof+mdat")
        }
    }

    private fun validateChildBoxLayout(data: ByteArray, start: Int, end: Int, label: String) {
        var pos = start
        while (pos < end) {
            val box = readRequiredBox(data, pos, end, label)
            if (!box.type.isPrintableMp4Type()) {
                throw AlacSegmentIntegrityException("ALAC $label had invalid atom type at offset $pos")
            }
            if (box.type in containerBoxes) {
                validateChildBoxLayout(data, box.payloadStart, box.end, "$label/${box.type}")
            }
            pos = box.end
        }
        if (pos != end) {
            throw AlacSegmentIntegrityException("ALAC $label had trailing malformed child bytes")
        }
    }

    private fun ByteArray.hasFragmentEncryptionBoxes(): Boolean {
        var found = false

        fun visit(start: Int, end: Int) {
            var pos = start
            while (pos < end && !found) {
                val box = readBox(this, pos, end) ?: return
                if (box.type in fragmentEncryptionBoxes) {
                    found = true
                    return
                }
                if (box.type in containerBoxes) {
                    visit(box.payloadStart, box.end)
                }
                pos = box.end
            }
        }

        visit(0, size)
        return found
    }

    private fun parseTfhd(data: ByteArray, box: Mp4Box): TfhdInfo {
        var pos = box.payloadStart
        val flags = readUInt32(data, pos).toInt() and 0xffffff
        pos += 4
        pos += 4

        var baseDataOffset: Long? = null
        var sampleDescriptionIndex = 0
        var defaultSampleSize = 0

        if (flags and 0x000001 != 0) {
            baseDataOffset = readUInt64(data, pos)
            pos += 8
        }
        if (flags and 0x000002 != 0) {
            sampleDescriptionIndex = readUInt32(data, pos).toInt() - 1
            pos += 4
        }
        if (flags and 0x000008 != 0) pos += 4
        if (flags and 0x000010 != 0) {
            defaultSampleSize = readUInt32(data, pos).toInt()
            pos += 4
        }
        if (flags and 0x000020 != 0) pos += 4

        return TfhdInfo(
            baseDataOffset = baseDataOffset,
            defaultBaseIsMoof = flags and 0x020000 != 0,
            sampleDescriptionIndex = sampleDescriptionIndex.coerceAtLeast(0),
            defaultSampleSize = defaultSampleSize
        )
    }

    private fun parseTrunSamples(
        data: ByteArray,
        trun: Mp4Box,
        moof: Mp4Box,
        mdat: Mp4Box,
        tfhd: TfhdInfo,
        implicitDataOffset: Int,
    ): TrunParseResult {
        var pos = trun.payloadStart
        val flags = readUInt32(data, pos).toInt() and 0xffffff
        pos += 4
        val sampleCount = readUInt32(data, pos).toInt()
        pos += 4

        val dataOffset = if (flags and 0x000001 != 0) {
            readInt32(data, pos).also { pos += 4 }
        } else {
            null
        }
        if (flags and 0x000004 != 0) pos += 4

        val baseOffset = tfhd.baseDataOffset
            ?: if (tfhd.defaultBaseIsMoof || dataOffset != null) moof.start.toLong() else mdat.payloadStart.toLong()
        var currentOffset = if (dataOffset != null) {
            (baseOffset + dataOffset).toInt()
        } else {
            implicitDataOffset
        }
        val samples = mutableListOf<SampleRange>()

        repeat(sampleCount) {
            if (flags and 0x000100 != 0) pos += 4
            val sampleSize = if (flags and 0x000200 != 0) {
                readUInt32(data, pos).toInt().also { pos += 4 }
            } else {
                tfhd.defaultSampleSize
            }
            if (flags and 0x000400 != 0) pos += 4
            if (flags and 0x000800 != 0) pos += 4

            samples += SampleRange(
                offset = currentOffset,
                size = sampleSize,
                sampleDescriptionIndex = tfhd.sampleDescriptionIndex
            )
            currentOffset += sampleSize
        }

        return TrunParseResult(samples = samples, nextImplicitOffset = currentOffset)
    }

    private fun patchInitSegment(
        initBytes: ByteArray,
        mode: AppleMusicWrapperManagerProvider.WrapperMode,
        durationMs: Long?,
        averageBitrate: Long?,
    ): ByteArray {
        var output = initBytes.copyOf()
        patchSampleEntries(output, 0, output.size, mode)
        output = patchTimelineDurations(output, durationMs)
        patchBitrateBoxes(output, averageBitrate)
        return output
    }

    private fun patchBitrateBoxes(data: ByteArray, averageBitrate: Long?) {
        val bitrate = averageBitrate?.takeIf { it > 0L } ?: return
        forEachChildBox(data, 0, data.size) { box ->
            patchBitrateBoxes(data, box, bitrate)
        }
    }

    private fun patchBitrateBoxes(data: ByteArray, box: Mp4Box, averageBitrate: Long) {
        if (box.type == "btrt" && box.payloadStart + 12 <= box.end) {
            val safeBitrate = averageBitrate.coerceAtMost(0xffffffffL)
            val bufferSize = (safeBitrate / 8L).coerceIn(1L, 0xffffffffL)
            writeUInt32(data, box.payloadStart, bufferSize)
            writeUInt32(data, box.payloadStart + 4, safeBitrate)
            writeUInt32(data, box.payloadStart + 8, safeBitrate)
            return
        }
        if (box.type in containerBoxes) {
            forEachChildBox(data, box.payloadStart, box.end) { child ->
                patchBitrateBoxes(data, child, averageBitrate)
            }
        }
    }

    private fun readInitSegmentQuality(initBytes: ByteArray): AlacQualityInfo? {
        val sampleEntryQuality = findAudioSampleEntryQuality(initBytes)
        val mediaHeaderQuality = findAudioMediaHeaderQuality(initBytes)
        return sampleEntryQuality.mergedWith(mediaHeaderQuality)
    }

    private fun readAlacRepairParams(initBytes: ByteArray): AlacRepairParams? {
        var found: AlacRepairParams? = null
        findAlacRepairParams(initBytes, 0, initBytes.size) { params ->
            if (found == null) found = params
        }
        return found
    }

    private fun findAlacRepairParams(
        data: ByteArray,
        start: Int,
        end: Int,
        onParams: (AlacRepairParams) -> Unit,
    ) {
        forEachChildBox(data, start, end) { box ->
            if (box.type == "stsd" && box.payloadStart + 8 <= box.end) {
                readStsdAlacRepairParams(data, box)?.let(onParams)
            }
            if (box.type in containerBoxes) {
                findAlacRepairParams(data, box.payloadStart, box.end, onParams)
            }
        }
    }

    private fun readStsdAlacRepairParams(data: ByteArray, stsd: Mp4Box): AlacRepairParams? {
        val entryCount = readUInt32(data, stsd.payloadStart + 4)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        var pos = stsd.payloadStart + 8
        var remaining = entryCount
        while (remaining > 0 && pos < stsd.end) {
            val entry = readBox(data, pos, stsd.end) ?: return null
            if (entry.type == "alac" || entry.type == "enca") {
                readAlacRepairParamsFromSampleEntry(data, entry)?.let { return it }
            }
            pos = entry.end
            remaining--
        }
        return null
    }

    private fun readAlacRepairParamsFromSampleEntry(data: ByteArray, entry: Mp4Box): AlacRepairParams? {
        val childStart = entry.payloadStart + 28
        if (childStart >= entry.end) return null
        findNextTopLevelBox(data, childStart, entry.end, "alac")
            ?.let { return readAlacSpecificRepairParams(data, it) }
        val wave = findNextTopLevelBox(data, childStart, entry.end, "wave") ?: return null
        return findNextTopLevelBox(data, wave.payloadStart, wave.end, "alac")
            ?.let { readAlacSpecificRepairParams(data, it) }
    }

    private fun readAlacSpecificRepairParams(data: ByteArray, box: Mp4Box): AlacRepairParams? {
        if (box.payloadStart + 28 > box.end) return null
        val maxSamplesPerFrame = readUInt32(data, box.payloadStart + 4)
            .takeIf { it in 1L..Int.MAX_VALUE.toLong() }
            ?.toInt()
            ?: return null
        val sampleSize = data[box.payloadStart + 9].toInt() and 0xff
        val channels = data[box.payloadStart + 13].toInt() and 0xff
        if (sampleSize !in 1..32 || channels !in 1..8) return null
        return AlacRepairParams(
            maxSamplesPerFrame = maxSamplesPerFrame,
            sampleSize = sampleSize,
            riceHistoryMult = data[box.payloadStart + 10].toInt() and 0xff,
            riceInitialHistory = data[box.payloadStart + 11].toInt() and 0xff,
            riceLimit = (data[box.payloadStart + 12].toInt() and 0xff).coerceAtMost(32),
            channels = channels,
        )
    }

    private fun findAudioSampleEntryQuality(data: ByteArray): AlacQualityInfo? {
        var found: AlacQualityInfo? = null
        findAudioSampleEntryQuality(data, 0, data.size) { quality ->
            found = found.mergedWith(quality)
        }
        return found
    }

    private fun findAudioSampleEntryQuality(
        data: ByteArray,
        start: Int,
        end: Int,
        onQuality: (AlacQualityInfo) -> Unit,
    ) {
        forEachChildBox(data, start, end) { box ->
            if (box.type == "stsd" && box.payloadStart + 8 <= box.end) {
                readStsdAudioQuality(data, box)?.let(onQuality)
            }
            if (box.type in containerBoxes) {
                findAudioSampleEntryQuality(data, box.payloadStart, box.end, onQuality)
            }
        }
    }

    private fun readStsdAudioQuality(data: ByteArray, stsd: Mp4Box): AlacQualityInfo? {
        var quality: AlacQualityInfo? = null
        val entryCount = readUInt32(data, stsd.payloadStart + 4).toInt()
        var pos = stsd.payloadStart + 8
        repeat(entryCount) {
            val entry = readBox(data, pos, stsd.end) ?: return@repeat
            if (entry.type in audioSampleEntryTypes) {
                quality = quality.mergedWith(readAudioSampleEntryQuality(data, entry))
                quality = quality.mergedWith(readSampleEntryChildQuality(data, entry))
            }
            pos = entry.end
        }
        return quality
    }

    private fun readAudioSampleEntryQuality(data: ByteArray, entry: Mp4Box): AlacQualityInfo? {
        if (entry.payloadStart + 28 > entry.end) return null
        val fixedSampleRate = readUInt32(data, entry.payloadStart + 24)
        val sampleRate = normalizeSampleRate((fixedSampleRate ushr 16).toInt())
        val bitDepth = readUInt16(data, entry.payloadStart + 18)
            .takeIf { it in 1..32 }
        return AlacQualityInfo(
            sampleRate = sampleRate,
            bitDepth = bitDepth,
        ).takeIf { it.hasAny() }
    }

    private fun readSampleEntryChildQuality(data: ByteArray, entry: Mp4Box): AlacQualityInfo? {
        val childStart = entry.payloadStart + 28
        if (childStart >= entry.end) return null
        var quality: AlacQualityInfo? = null
        forEachChildBox(data, childStart, entry.end) { child ->
            quality = quality.mergedWith(readNestedSampleEntryQuality(data, child))
        }
        return quality
    }

    private fun readNestedSampleEntryQuality(data: ByteArray, box: Mp4Box): AlacQualityInfo? {
        if (box.type == "alac") return readAlacSpecificConfigQuality(data, box)
        if (box.type == "btrt") return readBitrateBoxQuality(data, box)
        if (box.type in sampleEntryChildContainers) {
            var quality: AlacQualityInfo? = null
            forEachChildBox(data, box.payloadStart, box.end) { child ->
                quality = quality.mergedWith(readNestedSampleEntryQuality(data, child))
            }
            return quality
        }
        return null
    }

    private fun readAlacSpecificConfigQuality(data: ByteArray, box: Mp4Box): AlacQualityInfo? {
        if (box.payloadStart + 28 > box.end) return null
        val bitDepth = (data[box.payloadStart + 9].toInt() and 0xff)
            .takeIf { it in 1..32 }
        val averageBitrate = readUInt32(data, box.payloadStart + 20)
            .takeIf { it > 0L }
        val sampleRate = normalizeSampleRate(readUInt32(data, box.payloadStart + 24).toInt())
        return AlacQualityInfo(
            sampleRate = sampleRate,
            bitDepth = bitDepth,
            bandwidth = averageBitrate,
        ).takeIf { it.hasAny() }
    }

    private fun readBitrateBoxQuality(data: ByteArray, box: Mp4Box): AlacQualityInfo? {
        if (box.payloadStart + 12 > box.end) return null
        val averageBitrate = readUInt32(data, box.payloadStart + 8)
            .takeIf { it > 0L }
            ?: readUInt32(data, box.payloadStart + 4).takeIf { it > 0L }
        return AlacQualityInfo(bandwidth = averageBitrate).takeIf { it.hasAny() }
    }

    private fun findAudioMediaHeaderQuality(data: ByteArray): AlacQualityInfo? {
        var audioTimescale: Int? = null
        val fallbackTimescales = mutableListOf<Int>()
        forEachChildBox(data, 0, data.size) { moov ->
            if (moov.type != "moov") return@forEachChildBox
            forEachChildBox(data, moov.payloadStart, moov.end) { trak ->
                if (trak.type != "trak") return@forEachChildBox
                val mdia = findChildBox(data, trak, "mdia") ?: return@forEachChildBox
                val sampleRate = findChildBox(data, mdia, "mdhd")
                    ?.let { parseMediaHeaderTimescale(data, it) }
                    ?.let { normalizeSampleRate(it.toInt()) }
                    ?: return@forEachChildBox
                fallbackTimescales += sampleRate
                if (audioTimescale == null && mediaHandlerType(data, mdia) == "soun") {
                    audioTimescale = sampleRate
                }
            }
        }
        return AlacQualityInfo(
            sampleRate = audioTimescale ?: fallbackTimescales.firstOrNull()
        ).takeIf { it.hasAny() }
    }

    private fun repairAlacPackets(
        data: ByteArray,
        samples: List<SampleRange>,
        params: AlacRepairParams?,
    ): AlacPacketRepairStats {
        if (params == null || samples.isEmpty()) return AlacPacketRepairStats()
        var repaired = 0
        var unparseable = 0
        samples.forEach { sample ->
            val packet = data.copyOfRange(sample.offset, sample.offset + sample.size)
            val bodyEndBit = findAlacBodyEndBit(packet, params)
            if (bodyEndBit < 0) {
                unparseable++
                return@forEach
            }
            if (patchAlacPacketEndMarker(packet, bodyEndBit)) {
                packet.copyInto(data, destinationOffset = sample.offset)
                repaired++
            }
        }
        return AlacPacketRepairStats(repaired = repaired, unparseable = unparseable)
    }

    private fun findAlacBodyEndBit(packet: ByteArray, params: AlacRepairParams): Int {
        return try {
            val reader = AlacBitReader(packet)
            var channelsUsed = 0
            var lastEnd = -1
            while (reader.left() >= 3) {
                val element = scanOneAlacElement(reader, params)
                if (element.isEnd) return reader.position
                channelsUsed += element.channels
                lastEnd = reader.position
                if (channelsUsed >= params.channels) return lastEnd
            }
            lastEnd
        } catch (_: AlacPacketParseException) {
            -1
        }
    }

    private fun scanOneAlacElement(reader: AlacBitReader, params: AlacRepairParams): AlacElementScan {
        val element = reader.read(3).toInt()
        if (element == ALAC_TYPE_END) return AlacElementScan(channels = 0, isEnd = true)
        if (element > 1 && element != 3) {
            throw AlacPacketParseException("unsupported ALAC element tag $element")
        }

        val channels = if (element == 1) 2 else 1
        reader.skip(4)
        reader.skip(12)
        val hasSize = reader.read(1) != 0L
        val extraBits = reader.read(2).toInt() shl 3
        val bitsPerSample = params.sampleSize - extraBits + channels - 1
        if (bitsPerSample !in 1..32) {
            throw AlacPacketParseException("invalid ALAC bps $bitsPerSample")
        }
        val isCompressed = reader.read(1) == 0L
        val outputSamples = if (hasSize) {
            reader.read(32)
        } else {
            params.maxSamplesPerFrame.toLong()
        }
        if (outputSamples <= 0L || outputSamples > params.maxSamplesPerFrame.toLong()) {
            throw AlacPacketParseException("invalid ALAC sample count $outputSamples")
        }
        val sampleCount = outputSamples.toInt()

        if (isCompressed) {
            reader.skip(8)
            reader.skip(8)
            val riceHistoryMultipliers = IntArray(channels)
            repeat(channels) { channel ->
                reader.skip(4)
                val lpcQuant = reader.read(4)
                val riceHistoryMultiplier = reader.read(3).toInt()
                val lpcOrder = reader.read(5)
                if (lpcOrder >= params.maxSamplesPerFrame.toLong() || lpcQuant == 0L) {
                    throw AlacPacketParseException("invalid ALAC lpc params")
                }
                reader.skip(lpcOrder.toInt() * 16)
                riceHistoryMultipliers[channel] = riceHistoryMultiplier
            }
            if (extraBits != 0) {
                reader.skipChecked(sampleCount.toLong() * channels.toLong() * extraBits.toLong())
            }
            repeat(channels) { channel ->
                val effectiveHistoryMult =
                    (riceHistoryMultipliers[channel].toLong() * params.riceHistoryMult.toLong()) / 4L
                riceDecompress(reader, sampleCount, bitsPerSample, effectiveHistoryMult, params)
            }
        } else {
            reader.skipChecked(sampleCount.toLong() * channels.toLong() * params.sampleSize.toLong())
        }
        return AlacElementScan(channels = channels, isEnd = false)
    }

    private fun riceDecompress(
        reader: AlacBitReader,
        sampleCount: Int,
        bitsPerSample: Int,
        effectiveHistoryMult: Long,
        params: AlacRepairParams,
    ) {
        var history = params.riceInitialHistory.toLong()
        var signModifier = 0L
        val limit = params.riceLimit
        val iterationLimit = sampleCount * 4 + 100
        var iterations = 0
        var sampleIndex = 0
        while (sampleIndex < sampleCount) {
            iterations++
            if (iterations > iterationLimit) throw AlacPacketParseException("ALAC rice runaway")
            if (reader.left() <= 0) throw AlacPacketParseException("ALAC rice EOF")
            var k = alacAvLog2((history ushr 9) + 3L)
            if (k > limit) k = limit
            var x = decodeAlacScalar(reader, k, bitsPerSample)
            x += signModifier
            signModifier = 0L
            history = if (x > 0xffffL) {
                0xffffL
            } else {
                history + x * effectiveHistoryMult - ((history * effectiveHistoryMult) ushr 9)
            }
            if (history < 128L && sampleIndex + 1 < sampleCount) {
                var k2 = 7 - alacAvLog2(history) + (((history + 16L) ushr 6).toInt())
                if (k2 > limit) k2 = limit
                var blockSize = decodeAlacScalar(reader, k2, 16)
                if (blockSize > 0L) {
                    if (blockSize >= sampleCount - sampleIndex) {
                        blockSize = (sampleCount - sampleIndex - 1).toLong()
                    }
                    sampleIndex += blockSize.toInt()
                }
                if (blockSize <= 0xffffL) signModifier = 1L
                history = 0L
            }
            sampleIndex++
        }
    }

    private fun decodeAlacScalar(reader: AlacBitReader, k: Int, bitsPerSample: Int): Long {
        var x = reader.unary09()
        if (x > 8L) return reader.read(bitsPerSample)
        if (k <= 1) return x
        val extraBits = reader.show(k)
        x = (x shl k) - x
        if (extraBits > 1L) {
            x += extraBits - 1L
            reader.skip(k)
        } else {
            reader.skip(k - 1)
        }
        return x
    }

    private fun alacAvLog2(value: Long): Int {
        if (value <= 0L) return 0
        var current = value
        var result = 0
        while (current > 1L) {
            current = current ushr 1
            result++
        }
        return result
    }

    private fun patchAlacPacketEndMarker(packet: ByteArray, bodyEndBit: Int): Boolean {
        val totalBits = packet.size * 8
        if (bodyEndBit < 0 || bodyEndBit >= totalBits || bodyEndBit + 3 > totalBits) return false
        val reader = AlacBitReader(packet)
        return try {
            reader.skip(bodyEndBit)
            if (reader.left() >= 3 && reader.show(3).toInt() == ALAC_TYPE_END) return false
            repeat(3) { bit -> writePacketBit(packet, bodyEndBit + bit, true) }
            var bit = bodyEndBit + 3
            while (bit < totalBits) {
                writePacketBit(packet, bit, false)
                bit++
            }
            true
        } catch (_: AlacPacketParseException) {
            false
        }
    }

    private fun writePacketBit(packet: ByteArray, bitIndex: Int, value: Boolean) {
        val byteIndex = bitIndex / 8
        val mask = 1 shl (7 - (bitIndex % 8))
        val current = packet[byteIndex].toInt() and 0xff
        packet[byteIndex] = if (value) {
            (current or mask).toByte()
        } else {
            (current and mask.inv()).toByte()
        }
    }

    private fun stripFragmentEncryptionBoxes(data: ByteArray) {
        stripFragmentEncryptionBoxes(data, 0, data.size)
    }

    private fun stripFragmentEncryptionBoxes(data: ByteArray, start: Int, end: Int) {
        forEachChildBox(data, start, end) { box ->
            if (box.type in fragmentEncryptionBoxes) {
                data[box.start + 4] = 'f'.code.toByte()
                data[box.start + 5] = 'r'.code.toByte()
                data[box.start + 6] = 'e'.code.toByte()
                data[box.start + 7] = 'e'.code.toByte()
            } else if (box.type in containerBoxes) {
                stripFragmentEncryptionBoxes(data, box.payloadStart, box.end)
            }
        }
    }

    private fun patchSampleEntries(
        data: ByteArray,
        start: Int,
        end: Int,
        mode: AppleMusicWrapperManagerProvider.WrapperMode,
    ) {
        forEachChildBox(data, start, end) { box ->
            if (box.type == "stsd") {
                patchStsdEntries(data, box, mode)
            }
            if (box.type in containerBoxes) {
                patchSampleEntries(data, box.payloadStart, box.end, mode)
            }
        }
    }

    private fun patchStsdEntries(
        data: ByteArray,
        stsd: Mp4Box,
        mode: AppleMusicWrapperManagerProvider.WrapperMode,
    ) {
        var pos = stsd.payloadStart + 8
        val entryCount = readUInt32(data, stsd.payloadStart + 4).toInt()
        repeat(entryCount) {
            val entry = readBox(data, pos, stsd.end) ?: return
            if (entry.type == "enca") {
                mode.sampleEntryType.forEachIndexed { index, char ->
                    data[entry.start + 4 + index] = char.code.toByte()
                }
            }
            pos = entry.end
        }
    }

    private fun patchTimelineDurations(data: ByteArray, durationMs: Long?): ByteArray {
        val catalogDurationMs = durationMs?.takeIf { it > 0L } ?: return data
        val movieTimescale = findMovieTimescale(data)
        patchDurationBoxes(data, 0, data.size, catalogDurationMs, movieTimescale)
        return ensureMovieExtendsDurationBox(data, catalogDurationMs, movieTimescale)
    }

    private fun ensureMovieExtendsDurationBox(
        data: ByteArray,
        durationMs: Long,
        movieTimescale: Long?,
    ): ByteArray {
        val timescale = movieTimescale?.takeIf { it > 0L } ?: return data
        var moovBox: Mp4Box? = null
        forEachChildBox(data, 0, data.size) { box ->
            if (moovBox == null && box.type == "moov") moovBox = box
        }
        val moov = moovBox ?: return data
        val mvex = findChildBox(data, moov, "mvex") ?: return data
        if (findChildBox(data, mvex, "mehd") != null) return data
        if (moov.headerSize != 8 || mvex.headerSize != 8) return data

        val mehd = createMovieExtendsDurationBox(durationMs, timescale)
        val newMoovSize = (moov.end - moov.start).toLong() + mehd.size
        val newMvexSize = (mvex.end - mvex.start).toLong() + mehd.size
        if (newMoovSize > 0xffffffffL || newMvexSize > 0xffffffffL) return data

        val output = ByteArray(data.size + mehd.size)
        System.arraycopy(data, 0, output, 0, mvex.end)
        System.arraycopy(mehd, 0, output, mvex.end, mehd.size)
        System.arraycopy(data, mvex.end, output, mvex.end + mehd.size, data.size - mvex.end)
        writeUInt32(output, moov.start, newMoovSize)
        writeUInt32(output, mvex.start, newMvexSize)
        return output
    }

    private fun createMovieExtendsDurationBox(durationMs: Long, movieTimescale: Long): ByteArray {
        val duration = toTimescaleDuration(durationMs, movieTimescale)
        val version = if (duration > 0xffffffffL) 1 else 0
        val size = if (version == 1) 20 else 16
        val data = ByteArray(size)
        writeUInt32(data, 0, size.toLong())
        writeAscii(data, 4, "mehd")
        data[8] = version.toByte()
        if (version == 1) {
            writeUInt64(data, 12, duration)
        } else {
            writeUInt32(data, 12, duration)
        }
        return data
    }

    private fun findMovieTimescale(data: ByteArray): Long? {
        var timescale: Long? = null
        forEachChildBox(data, 0, data.size) { box ->
            if (box.type == "moov" && timescale == null) {
                findChildBox(data, box, "mvhd")?.let { mvhd ->
                    timescale = parseMovieHeaderTimescale(data, mvhd)
                }
            }
        }
        return timescale
    }

    private fun patchDurationBoxes(
        data: ByteArray,
        start: Int,
        end: Int,
        durationMs: Long,
        movieTimescale: Long?,
    ) {
        forEachChildBox(data, start, end) { box ->
            when (box.type) {
                "mvhd" -> patchMovieHeaderDuration(data, box, durationMs)
                "tkhd" -> patchTrackHeaderDuration(data, box, durationMs, movieTimescale)
                "mdhd" -> patchMediaHeaderDuration(data, box, durationMs)
                "mehd" -> patchMovieExtendsDuration(data, box, durationMs, movieTimescale)
                "elst" -> patchEditListDuration(data, box, durationMs, movieTimescale)
            }
            if (box.type in containerBoxes) {
                patchDurationBoxes(data, box.payloadStart, box.end, durationMs, movieTimescale)
            }
        }
    }

    private fun parseMovieHeaderTimescale(data: ByteArray, box: Mp4Box): Long? {
        val version = fullBoxVersion(data, box)
        val timescaleOffset = if (version == 1) box.payloadStart + 20 else box.payloadStart + 12
        if (timescaleOffset + 4 > box.end) return null
        return readUInt32(data, timescaleOffset).takeIf { it > 0L }
    }

    private fun parseMediaHeaderTimescale(data: ByteArray, box: Mp4Box): Long? {
        val version = fullBoxVersion(data, box)
        val timescaleOffset = if (version == 1) box.payloadStart + 20 else box.payloadStart + 12
        if (timescaleOffset + 4 > box.end) return null
        return readUInt32(data, timescaleOffset).takeIf { it > 0L }
    }

    private fun mediaHandlerType(data: ByteArray, mdia: Mp4Box): String? {
        val hdlr = findChildBox(data, mdia, "hdlr") ?: return null
        val handlerTypeOffset = hdlr.payloadStart + 8
        if (handlerTypeOffset + 4 > hdlr.end) return null
        return data.copyOfRange(handlerTypeOffset, handlerTypeOffset + 4)
            .toString(Charsets.ISO_8859_1)
    }

    private fun patchMovieHeaderDuration(data: ByteArray, box: Mp4Box, durationMs: Long) {
        val version = fullBoxVersion(data, box)
        val timescaleOffset = if (version == 1) box.payloadStart + 20 else box.payloadStart + 12
        val durationOffset = if (version == 1) box.payloadStart + 24 else box.payloadStart + 16
        patchTimescaledDuration(
            data = data,
            box = box,
            timescaleOffset = timescaleOffset,
            durationOffset = durationOffset,
            durationBytes = if (version == 1) 8 else 4,
            durationMs = durationMs
        )
    }

    private fun patchTrackHeaderDuration(
        data: ByteArray,
        box: Mp4Box,
        durationMs: Long,
        movieTimescale: Long?,
    ) {
        val timescale = movieTimescale?.takeIf { it > 0L } ?: return
        val version = fullBoxVersion(data, box)
        val durationOffset = if (version == 1) box.payloadStart + 28 else box.payloadStart + 20
        val durationBytes = if (version == 1) 8 else 4
        if (durationOffset + durationBytes > box.end) return
        writeDurationValue(
            data = data,
            offset = durationOffset,
            bytes = durationBytes,
            value = toTimescaleDuration(durationMs, timescale)
        )
    }

    private fun patchMediaHeaderDuration(data: ByteArray, box: Mp4Box, durationMs: Long) {
        val version = fullBoxVersion(data, box)
        val timescaleOffset = if (version == 1) box.payloadStart + 20 else box.payloadStart + 12
        val durationOffset = if (version == 1) box.payloadStart + 24 else box.payloadStart + 16
        patchTimescaledDuration(
            data = data,
            box = box,
            timescaleOffset = timescaleOffset,
            durationOffset = durationOffset,
            durationBytes = if (version == 1) 8 else 4,
            durationMs = durationMs
        )
    }

    private fun patchMovieExtendsDuration(
        data: ByteArray,
        box: Mp4Box,
        durationMs: Long,
        movieTimescale: Long?,
    ) {
        val timescale = movieTimescale?.takeIf { it > 0L } ?: return
        val version = fullBoxVersion(data, box)
        val durationOffset = box.payloadStart + 4
        val durationBytes = if (version == 1) 8 else 4
        if (durationOffset + durationBytes > box.end) return
        writeDurationValue(
            data = data,
            offset = durationOffset,
            bytes = durationBytes,
            value = toTimescaleDuration(durationMs, timescale)
        )
    }

    private fun patchEditListDuration(
        data: ByteArray,
        box: Mp4Box,
        durationMs: Long,
        movieTimescale: Long?,
    ) {
        val timescale = movieTimescale?.takeIf { it > 0L } ?: return
        if (box.payloadStart + 8 > box.end) return
        val version = fullBoxVersion(data, box)
        val entryCount = readUInt32(data, box.payloadStart + 4).toInt()
        if (entryCount != 1) return
        val durationOffset = box.payloadStart + 8
        val durationBytes = if (version == 1) 8 else 4
        if (durationOffset + durationBytes > box.end) return
        writeDurationValue(data, durationOffset, durationBytes, toTimescaleDuration(durationMs, timescale))
    }

    private fun patchTimescaledDuration(
        data: ByteArray,
        box: Mp4Box,
        timescaleOffset: Int,
        durationOffset: Int,
        durationBytes: Int,
        durationMs: Long,
    ) {
        if (timescaleOffset + 4 > box.end || durationOffset + durationBytes > box.end) return
        val timescale = readUInt32(data, timescaleOffset).takeIf { it > 0L } ?: return
        writeDurationValue(data, durationOffset, durationBytes, toTimescaleDuration(durationMs, timescale))
    }

    private fun fullBoxVersion(data: ByteArray, box: Mp4Box): Int {
        return if (box.payloadStart < box.end) data[box.payloadStart].toInt() and 0xff else 0
    }

    private fun toTimescaleDuration(durationMs: Long, timescale: Long): Long {
        return ((durationMs * timescale) + 500L) / 1000L
    }

    private fun writeDurationValue(data: ByteArray, offset: Int, bytes: Int, value: Long) {
        if (bytes == 8) {
            writeUInt64(data, offset, value)
        } else {
            writeUInt32(data, offset, value.coerceAtMost(0xffffffffL))
        }
    }

    private fun findChildBox(data: ByteArray, parent: Mp4Box, type: String): Mp4Box? {
        var found: Mp4Box? = null
        forEachChildBox(data, parent.payloadStart, parent.end) { child ->
            if (found == null && child.type == type) found = child
        }
        return found
    }

    private fun findNextTopLevelBox(data: ByteArray, start: Int, end: Int, type: String): Mp4Box? {
        var pos = start
        while (pos < end) {
            val box = readBox(data, pos, end) ?: return null
            if (box.type == type) return box
            pos = box.end
        }
        return null
    }

    private fun forEachChildBox(data: ByteArray, start: Int, end: Int, block: (Mp4Box) -> Unit) {
        var pos = start
        while (pos < end) {
            val box = readBox(data, pos, end) ?: return
            block(box)
            pos = box.end
        }
    }

    private fun readBox(data: ByteArray, start: Int, limit: Int): Mp4Box? {
        if (start + 8 > limit) return null
        val size32 = readUInt32(data, start)
        val type = data.copyOfRange(start + 4, start + 8).toString(Charsets.ISO_8859_1)
        var headerSize = 8
        val boxSize = when (size32) {
            0L -> (limit - start).toLong()
            1L -> {
                if (start + 16 > limit) return null
                headerSize = 16
                readUInt64(data, start + 8)
            }
            else -> size32
        }
        if (boxSize < headerSize || boxSize > Int.MAX_VALUE) return null
        val end = start + boxSize.toInt()
        if (end <= start || end > limit) return null
        return Mp4Box(type = type, start = start, headerSize = headerSize, end = end)
    }

    private fun readRequiredBox(data: ByteArray, start: Int, limit: Int, label: String): Mp4Box {
        if (start + 8 > limit) {
            throw AlacSegmentIntegrityException("ALAC $label had an incomplete atom header at offset $start")
        }
        val size32 = readUInt32(data, start)
        val type = data.copyOfRange(start + 4, start + 8).toString(Charsets.ISO_8859_1)
        var headerSize = 8
        val boxSize = when (size32) {
            0L -> (limit - start).toLong()
            1L -> {
                if (start + 16 > limit) {
                    throw AlacSegmentIntegrityException(
                        "ALAC $label had an incomplete large-size atom header at offset $start"
                    )
                }
                headerSize = 16
                readUInt64(data, start + 8)
            }
            else -> size32
        }
        if (boxSize > Int.MAX_VALUE) {
            throw AlacSegmentIntegrityException(
                "ALAC $label had unsupported atom length $boxSize for $type at offset $start"
            )
        }
        if (boxSize < headerSize) {
            throw AlacSegmentIntegrityException("ALAC $label had invalid atom length $boxSize for $type at offset $start")
        }
        val end = start + boxSize.toInt()
        if (end <= start || end > limit) {
            throw AlacSegmentIntegrityException(
                "ALAC $label atom $type at offset $start overran fragment bounds"
            )
        }
        return Mp4Box(type = type, start = start, headerSize = headerSize, end = end)
    }

    private fun String.isPrintableMp4Type(): Boolean {
        return length == 4 && all { it.code in 0x20..0x7e }
    }

    private fun downloadText(client: OkHttpClient, url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                    "ALAC playlist request failed with HTTP ${response.code} at ${request.url.host}"
                )
            }
            return (response.body
                ?: throw AppleMusicWrapperManagerProvider.WrapperManagerException("ALAC playlist response had no body"))
                .string()
        }
    }

    private fun downloadBytes(client: OkHttpClient, url: String, range: ResolvedByteRange? = null): ByteArray {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
        if (range != null) {
            requestBuilder.header("Range", "bytes=${range.offset}-${range.offset + range.length - 1}")
        }
        val request = requestBuilder.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                    "ALAC media request failed with HTTP ${response.code} at ${request.url.host}"
                )
            }
            val bytes = (response.body
                ?: throw AppleMusicWrapperManagerProvider.WrapperManagerException("ALAC media response had no body"))
                .bytes()
            if (range != null && response.code == 200 && bytes.size.toLong() > range.length) {
                val from = range.offset.toInt()
                val to = (range.offset + range.length).toInt()
                if (from >= 0 && to <= bytes.size && from < to) return bytes.copyOfRange(from, to)
            }
            return bytes
        }
    }

    private fun resolveUri(baseUrl: String, uri: String): String {
        if (uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)) {
            return uri
        }
        val base = baseUrl.toHttpUrlOrNull() ?: return uri
        return base.resolve(uri)?.toString() ?: uri
    }

    private fun resolveKeyUri(baseUrl: String, uri: String): String {
        if (uri.startsWith("skd://", ignoreCase = true)) return uri
        return resolveUri(baseUrl, uri)
    }

    private fun parseByteRangeSpec(value: String): ByteRangeSpec {
        val parts = value.trim().trim('"').split("@", limit = 2)
        val length = parts.getOrNull(0)?.toLongOrNull()
            ?: throw AppleMusicWrapperManagerProvider.WrapperManagerException("Invalid HLS byte range: $value")
        val offset = parts.getOrNull(1)?.toLongOrNull()
        return ByteRangeSpec(length = length, offset = offset)
    }

    private fun resolveByteRange(
        url: String,
        spec: ByteRangeSpec,
        nextOffsetByUrl: MutableMap<String, Long>,
    ): ResolvedByteRange {
        val offset = spec.offset ?: nextOffsetByUrl[url] ?: 0L
        nextOffsetByUrl[url] = offset + spec.length
        return ResolvedByteRange(offset = offset, length = spec.length)
    }

    private fun parseAttributes(raw: String): Map<String, String> {
        val attrs = linkedMapOf<String, String>()
        var index = 0
        while (index < raw.length) {
            while (index < raw.length && (raw[index] == ',' || raw[index].isWhitespace())) index++
            val keyStart = index
            while (index < raw.length && raw[index] != '=' && raw[index] != ',') index++
            val key = raw.substring(keyStart, index).trim()
            if (key.isEmpty()) {
                index++
                continue
            }
            if (index >= raw.length || raw[index] != '=') {
                attrs[key] = "true"
                continue
            }
            index++
            val value = if (index < raw.length && raw[index] == '"') {
                index++
                val valueStart = index
                while (index < raw.length && raw[index] != '"') index++
                raw.substring(valueStart, index).also {
                    if (index < raw.length && raw[index] == '"') index++
                }
            } else {
                val valueStart = index
                while (index < raw.length && raw[index] != ',') index++
                raw.substring(valueStart, index).trim()
            }
            attrs[key] = value
            if (index < raw.length && raw[index] == ',') index++
        }
        return attrs
    }

    private fun Map<String, String>.toQualityInfo(): AlacQualityInfo? {
        return AlacQualityInfo(
            sampleRate = firstInt("SAMPLE-RATE", "SAMPLERATE", "sample_rate"),
            bitDepth = firstInt("BIT-DEPTH", "BIT_DEPTH", "bit_depth"),
            bandwidth = firstLong("AVERAGE-BANDWIDTH", "BANDWIDTH")
        ).takeIf { it.hasAny() }
    }

    private fun Map<String, String>.firstInt(vararg keys: String): Int? {
        return keys.firstNotNullOfOrNull { key -> this[key]?.toIntOrNull() }
    }

    private fun Map<String, String>.firstLong(vararg keys: String): Long? {
        return keys.firstNotNullOfOrNull { key -> this[key]?.toLongOrNull() }
    }

    private fun AlacQualityInfo?.mergedWith(other: AlacQualityInfo?): AlacQualityInfo? {
        if (this == null) return other
        if (other == null) return this
        return AlacQualityInfo(
            sampleRate = sampleRate ?: other.sampleRate,
            bitDepth = bitDepth ?: other.bitDepth,
            bandwidth = other.bandwidth ?: bandwidth
        ).takeIf { it.hasAny() }
    }

    private fun AlacQualityInfo.toMetadata(): AlacQualityMetadata? {
        val filteredBandwidth = bandwidth
            ?.takeIf { isPlausibleAlacBandwidth(it, sampleRate) }
        return AlacQualityMetadata(
            bitrate = filteredBandwidth
                ?.takeIf { it > 0L }
                ?.coerceAtMost(Int.MAX_VALUE.toLong())
                ?.toInt()
                ?: 0,
            sampleRate = sampleRate,
            bitDepth = bitDepth
        ).takeIf { it.hasAny() }
    }

    private fun AlacQualityInfo.toTraceValue(): String {
        return listOfNotNull(
            sampleRate?.let { "sampleRate=$it" },
            bitDepth?.let { "bitDepth=$it" },
            bandwidth?.takeIf { isPlausibleAlacBandwidth(it, sampleRate) }?.let { "bitrate=$it" },
        ).joinToString(" ")
    }

    private fun isPlausibleAlacBandwidth(
        bandwidth: Long,
        sampleRate: Int?,
    ): Boolean {
        if (bandwidth <= 0L) return false
        if (bandwidth == LEGACY_ALAC_PLACEHOLDER_BANDWIDTH) return false
        val max = when {
            sampleRate == null -> 6_500_000L
            sampleRate <= 48_000 -> 2_400_000L
            sampleRate <= 96_000 -> 5_500_000L
            else -> 10_000_000L
        }
        return bandwidth in 128_000L..max
    }

    private fun readUInt32(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xff) shl 24) or
            ((data[offset + 1].toLong() and 0xff) shl 16) or
            ((data[offset + 2].toLong() and 0xff) shl 8) or
            (data[offset + 3].toLong() and 0xff)
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xff) shl 8) or
            (data[offset + 1].toInt() and 0xff)
    }

    private fun readInt32(data: ByteArray, offset: Int): Int {
        return readUInt32(data, offset).toInt()
    }

    private fun readUInt64(data: ByteArray, offset: Int): Long {
        return (readUInt32(data, offset) shl 32) or readUInt32(data, offset + 4)
    }

    private fun writeUInt32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = ((value ushr 24) and 0xff).toByte()
        data[offset + 1] = ((value ushr 16) and 0xff).toByte()
        data[offset + 2] = ((value ushr 8) and 0xff).toByte()
        data[offset + 3] = (value and 0xff).toByte()
    }

    private fun writeUInt16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value ushr 8) and 0xff).toByte()
        data[offset + 1] = (value and 0xff).toByte()
    }

    private fun writeUInt64(data: ByteArray, offset: Int, value: Long) {
        writeUInt32(data, offset, value ushr 32)
        writeUInt32(data, offset + 4, value)
    }

    private fun writeAscii(data: ByteArray, offset: Int, value: String) {
        value.toByteArray(Charsets.ISO_8859_1).copyInto(data, offset)
    }

    private fun ByteArray.cbcsDecryptLength(): Int {
        return size - (size % CBCS_BLOCK_SIZE)
    }

    private fun segmentDecryptContext(segmentIndex: Int): String {
        return "segment:$segmentIndex"
    }

    private fun AppleMusicWrapperManagerProvider.WrapperManagerException.isSampleIntegrityFailure(): Boolean {
        val text = message.orEmpty()
        return text.contains("did not return usable sample", ignoreCase = true) ||
            text.contains("sample", ignoreCase = true) && text.contains("size mismatch", ignoreCase = true)
    }

    private val containerBoxes = setOf(
        "moov", "trak", "mdia", "minf", "stbl", "edts", "moof", "traf", "mvex"
    )

    private val audioSampleEntryTypes = setOf(
        "alac", "enca", "mp4a", "ec-3", "ac-3"
    )

    private val sampleEntryChildContainers = setOf(
        "sinf", "schi", "wave"
    )

    private val fragmentEncryptionBoxes = setOf(
        "saiz", "saio", "senc", "sbgp", "sgpd"
    )

    private fun normalizeSampleRate(value: Int): Int? {
        return value.takeIf { it in 8_000..384_000 }
    }

    private data class PlaylistDocument(
        val url: String,
        val text: String,
        val qualityInfo: AlacQualityInfo?,
    )

    private data class PlaylistSelection(
        val url: String,
        val qualityInfo: AlacQualityInfo?,
    ) {
        fun withQuality(streamQuality: AlacQualityInfo?): PlaylistSelection {
            return copy(qualityInfo = qualityInfo.mergedWith(streamQuality))
        }
    }

    private data class AlacQualityInfo(
        val sampleRate: Int? = null,
        val bitDepth: Int? = null,
        val bandwidth: Long? = null,
    ) {
        fun hasAny() = sampleRate != null || bitDepth != null || bandwidth != null

        fun toLabel(): String? {
            val parts = listOfNotNull(
                sampleRate?.let { "${formatKhz(it)}kHz" },
                bandwidth
                    ?.takeIf { isPlausibleAlacBandwidth(it, sampleRate) }
                    ?.let { "${(it / 1000L).coerceAtLeast(1L)}kbps" },
                bitDepth?.let { "${it}-bit" }
            )
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
        }

        private fun formatKhz(value: Int): String {
            val khz = value / 1000.0
            return if (value % 1000 == 0) {
                String.format(Locale.US, "%.0f", khz)
            } else {
                String.format(Locale.US, "%.1f", khz)
            }
        }
    }

    private const val LEGACY_ALAC_PLACEHOLDER_BANDWIDTH = 4_000_000L

    private data class MediaPlaylist(
        val init: InitSegment,
        val segments: List<MediaSegment>,
        val keyRing: List<String>,
        val durationMs: Long?,
        val qualityInfo: AlacQualityInfo?,
    )

    private data class InitSegment(
        val url: String,
        val range: ResolvedByteRange?,
    )

    private data class MediaSegment(
        val url: String,
        val range: ResolvedByteRange?,
        val keyUri: String?,
        val durationMs: Long?,
    )

    private data class ByteRangeSpec(
        val length: Long,
        val offset: Long?,
    )

    private data class ResolvedByteRange(
        val offset: Long,
        val length: Long,
    )

    private data class DecryptedSegment(
        val bytes: ByteArray,
        val sampleCount: Int,
        val firstSampleIndex: Int,
    )

    private data class EncryptedSegment(
        val bytes: ByteArray,
        val samples: List<SampleRange>,
        val estimatedFirstSampleIndex: Int? = null,
    ) {
        val sampleCount: Int get() = samples.size
    }

    private data class SampleRange(
        val offset: Int,
        val size: Int,
        val sampleDescriptionIndex: Int,
    )

    private data class AlacRepairParams(
        val maxSamplesPerFrame: Int,
        val sampleSize: Int,
        val riceHistoryMult: Int,
        val riceInitialHistory: Int,
        val riceLimit: Int,
        val channels: Int,
    )

    private data class AlacPacketRepairStats(
        val repaired: Int = 0,
        val unparseable: Int = 0,
    )

    private data class AlacElementScan(
        val channels: Int,
        val isEnd: Boolean,
    )

    private class AlacPacketParseException(message: String) : IOException(message)

    private class AlacBitReader(private val data: ByteArray) {
        private val bitCount = data.size * 8
        var position: Int = 0
            private set

        fun left(): Int = bitCount - position

        fun read(bits: Int): Long {
            if (bits < 0 || bits > 32 || position + bits > bitCount) {
                throw AlacPacketParseException("ALAC bit reader EOF")
            }
            var value = 0L
            var pos = position
            repeat(bits) {
                val bit = (data[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1
                value = (value shl 1) or bit.toLong()
                pos++
            }
            position = pos
            return value
        }

        fun show(bits: Int): Long {
            val saved = position
            val value = read(bits)
            position = saved
            return value
        }

        fun skip(bits: Int) {
            if (bits < 0 || position + bits > bitCount) {
                throw AlacPacketParseException("ALAC bit reader EOF")
            }
            position += bits
        }

        fun skipChecked(bits: Long) {
            if (bits > Int.MAX_VALUE) throw AlacPacketParseException("ALAC packet field too large")
            skip(bits.toInt())
        }

        fun unary09(): Long {
            var count = 0L
            while (count < 9L) {
                if (read(1) == 0L) return count
                count++
            }
            return 9L
        }
    }

    private data class SampleDecryptRequest(
        val key: String,
        val range: SampleRange,
        val decryptLength: Int,
        val request: AppleMusicWrapperManagerProvider.DecryptSample,
    )

    private data class Mp4Box(
        val type: String,
        val start: Int,
        val headerSize: Int,
        val end: Int,
    ) {
        val payloadStart: Int get() = start + headerSize
    }

    private data class TfhdInfo(
        val baseDataOffset: Long? = null,
        val defaultBaseIsMoof: Boolean = false,
        val sampleDescriptionIndex: Int = 0,
        val defaultSampleSize: Int = 0,
    )

    private data class TrunParseResult(
        val samples: List<SampleRange>,
        val nextImplicitOffset: Int,
    )
}

