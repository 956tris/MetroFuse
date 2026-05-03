package com.metrolist.music.apple

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

object AppleMusicDecryptPipeline {
    private const val USER_AGENT = "Echo-TidalPlus/AppleMusicWrapperManager"
    private const val PREFETCH_KEY = "skd://itunes.apple.com/P000000000/s1/e1"
    private const val KEY_SUFFIX_ALAC = "c23"
    private const val KEY_SUFFIX_DEFAULT = "c6"
    private const val DEFAULT_PREFETCH_WINDOW_SEGMENTS = 4
    private const val DEFAULT_PREFETCH_CONCURRENCY = 2
    private const val DEFAULT_STARTUP_READY_SEGMENTS = 2
    private const val HIGH_PREFETCH_WINDOW_SEGMENTS = 6
    private const val HIGH_PREFETCH_CONCURRENCY = 4
    private const val HIGH_STARTUP_READY_SEGMENTS = 3
    private val segmentSampleCountCache = ConcurrentHashMap<String, Int>()
    private val segmentLengthCache = ConcurrentHashMap<String, Long>()
    private val encryptedSegmentCache = ConcurrentHashMap<String, ByteArray>()

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

    fun clearMemoryCaches() {
        segmentSampleCountCache.clear()
        segmentLengthCache.clear()
        encryptedSegmentCache.clear()
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
        if (mode != AppleMusicWrapperManagerProvider.WrapperMode.ALAC) {
            throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                "${mode.title} is not supported by the ALAC decrypt pipeline"
            )
        }

        val mediaDocument = resolveToMediaPlaylist(client, m3u8Url)
        val mediaPlaylist = parseMediaPlaylist(mediaDocument.url, mediaDocument.text)
        val segmentLengths = resolveSegmentLengths(client, mediaPlaylist.segments)
        val averageBitrate = estimateAverageBandwidth(mediaPlaylist, segmentLengths)
        val patchedInitBytes = patchInitSegment(
            initBytes = downloadBytes(client, mediaPlaylist.init.url, mediaPlaylist.init.range),
            durationMs = durationMs?.takeIf { it > 0L } ?: mediaPlaylist.durationMs,
            averageBitrate = averageBitrate
        )
        val initBytes = buildSeekableInitSegment(patchedInitBytes, mediaPlaylist, segmentLengths)
        val totalLength = segmentLengths?.fold(initBytes.size.toLong()) { total, length -> total + length }

        val stream = DecryptedAlacInputStream(
            initBytes = initBytes,
            playlist = mediaPlaylist,
            segmentLengths = segmentLengths,
            adamId = adamId,
            host = host,
            secure = secure,
            mode = mode,
            client = client,
            startOffset = start.coerceAtLeast(0L),
            prefetchWindow = if (highWorkerMode) HIGH_PREFETCH_WINDOW_SEGMENTS else DEFAULT_PREFETCH_WINDOW_SEGMENTS,
            prefetchConcurrency = if (highWorkerMode) HIGH_PREFETCH_CONCURRENCY else DEFAULT_PREFETCH_CONCURRENCY,
            startupReadySegments = if (highWorkerMode) HIGH_STARTUP_READY_SEGMENTS else DEFAULT_STARTUP_READY_SEGMENTS
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

    fun readAlacQualityMetadata(client: OkHttpClient, initialUrl: String): AlacQualityMetadata? {
        return readAlacQualityInfo(client, initialUrl)?.toMetadata()
    }

    fun describeAlacQuality(client: OkHttpClient, initialUrl: String): String? {
        return readAlacQualityMetadata(client, initialUrl)?.toLabel()
    }

    private fun readAlacQualityInfo(client: OkHttpClient, initialUrl: String): AlacQualityInfo? {
        var currentUrl = initialUrl
        var currentText = downloadText(client, currentUrl)
        var qualityInfo: AlacQualityInfo? = null

        repeat(4) {
            if (!currentText.isMasterPlaylist()) {
                val mediaQuality = runCatching {
                    val mediaPlaylist = parseMediaPlaylist(currentUrl, currentText)
                    val initQuality = runCatching {
                        readInitSegmentQuality(
                            downloadBytes(client, mediaPlaylist.init.url, mediaPlaylist.init.range)
                        )
                    }.getOrNull()
                    AlacQualityInfo(
                        bandwidth = estimateAverageBandwidth(
                            client = client,
                            playlist = mediaPlaylist,
                            maxRemoteSegments = 4
                        )
                    ).mergedWith(initQuality)
                }.getOrNull()
                return qualityInfo.mergedWith(mediaQuality)
            }
            val child = selectAlacChildPlaylist(currentUrl, currentText)
                ?: return qualityInfo
            qualityInfo = qualityInfo.mergedWith(child.qualityInfo)
            currentUrl = child.url
            currentText = downloadText(client, currentUrl)
        }
        return qualityInfo
    }

    private class DecryptedAlacInputStream(
        private val initBytes: ByteArray,
        private val playlist: MediaPlaylist,
        private val segmentLengths: List<Long>?,
        private val adamId: String,
        private val host: String,
        private val secure: Boolean,
        private val mode: AppleMusicWrapperManagerProvider.WrapperMode,
        private val client: OkHttpClient,
        private val startOffset: Long,
        private val prefetchWindow: Int,
        prefetchConcurrency: Int,
        private val startupReadySegments: Int,
    ) : InputStream() {
        private var current: InputStream? = null
        private var segmentIndex = 0
        private var nextSampleIndex = 0
        private val prefetchExecutor = Executors.newFixedThreadPool(prefetchConcurrency.coerceAtLeast(1)) { runnable ->
            Thread(runnable, "AppleWrapperAlacPrefetch").apply { isDaemon = true }
        }
        private val prefetched = ConcurrentHashMap<Int, CompletableFuture<DecryptedSegment>>()

        init {
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
            while (true) {
                val active = current ?: return -1
                val read = active.read(buffer, off, len)
                if (read != -1) return read
                active.close()
                current = openNextSegmentStream() ?: return -1
            }
        }

        override fun close() {
            current?.close()
            current = null
            prefetched.values.forEach { it.cancel(true) }
            prefetched.clear()
            prefetchExecutor.shutdownNow()
        }

        private fun openNextSegmentStream(): InputStream? {
            if (segmentIndex >= playlist.segments.size) return null
            schedulePrefetch()
            val decrypted = prefetched.remove(segmentIndex)
                ?.let { awaitPrefetchedSegment(it) }
                ?: decryptSegment(segmentIndex, nextSampleIndex)
            segmentIndex++
            nextSampleIndex += decrypted.sampleCount
            schedulePrefetch()
            return ByteArrayInputStream(decrypted.bytes)
        }

        private fun openAtByteOffset(offset: Long) {
            if (offset <= 0L) {
                current = ByteArrayInputStream(initBytes)
                segmentIndex = 0
                nextSampleIndex = 0
                return
            }

            val lengths = segmentLengths
            if (lengths == null) {
                current = ByteArrayInputStream(initBytes)
                skip(offset)
                return
            }

            if (offset < initBytes.size) {
                val initOffset = offset.toInt()
                current = ByteArrayInputStream(initBytes, initOffset, initBytes.size - initOffset)
                segmentIndex = 0
                nextSampleIndex = 0
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
            if (segmentIndex >= playlist.segments.size) {
                current = null
                return
            }

            val decrypted = decryptSegment(segmentIndex, nextSampleIndex)
            segmentIndex++
            nextSampleIndex += decrypted.sampleCount
            val segmentOffset = remaining.coerceIn(0L, decrypted.bytes.size.toLong()).toInt()
            current = ByteArrayInputStream(decrypted.bytes, segmentOffset, decrypted.bytes.size - segmentOffset)
        }

        private fun schedulePrefetch() {
            if (segmentIndex >= playlist.segments.size || prefetchExecutor.isShutdown) return
            var index = segmentIndex
            var scheduled = 0
            while (index < playlist.segments.size && scheduled < prefetchWindow) {
                val targetIndex = index
                prefetched.computeIfAbsent(targetIndex) {
                    CompletableFuture.supplyAsync(
                        { decryptSegment(targetIndex, firstSampleIndexForSegment(targetIndex)) },
                        prefetchExecutor
                    )
                }
                index++
                scheduled++
            }
        }

        private fun warmStartupBuffer() {
            val lastIndex = min(playlist.segments.size, segmentIndex + startupReadySegments)
            for (index in segmentIndex until lastIndex) {
                prefetched[index]?.let { awaitPrefetchedSegment(it) }
            }
        }

        private fun decryptSegment(index: Int, firstSample: Int): DecryptedSegment {
            return decryptSegmentBytes(
                client = client,
                adamId = adamId,
                host = host,
                secure = secure,
                mode = mode,
                playlist = playlist,
                segment = playlist.segments[index],
                firstSampleIndex = firstSample
            )
        }

        private fun firstSampleIndexForSegment(index: Int): Int {
            if (index == segmentIndex) return nextSampleIndex
            var firstSample = 0
            for (i in 0 until index) {
                firstSample += getSegmentSampleCount(client, playlist.segments[i])
            }
            return firstSample
        }

        private fun awaitPrefetchedSegment(future: CompletableFuture<DecryptedSegment>?): DecryptedSegment {
            return try {
                future?.get()
                    ?: throw AppleMusicWrapperManagerProvider.WrapperManagerException("ALAC prefetch was not scheduled")
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw AppleMusicWrapperManagerProvider.WrapperManagerException("ALAC prefetch was interrupted", error)
            } catch (error: ExecutionException) {
                val cause = error.cause
                if (cause is AppleMusicWrapperManagerProvider.WrapperManagerException) throw cause
                throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                    "ALAC prefetch failed: ${cause?.message ?: error.message ?: "unknown error"}",
                    cause ?: error
                )
            }
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

    private fun resolveToMediaPlaylist(client: OkHttpClient, initialUrl: String): PlaylistDocument {
        var currentUrl = initialUrl
        var currentText = downloadText(client, currentUrl)
        repeat(4) {
            if (!currentText.isMasterPlaylist()) {
                return PlaylistDocument(currentUrl, currentText)
            }
            val childUrl = selectAlacChildPlaylist(currentUrl, currentText)?.url
                ?: throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                    "wrapper-manager M3U8 did not expose an ALAC media playlist"
                )
            currentUrl = childUrl
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

    private fun selectAlacChildPlaylist(baseUrl: String, playlist: String): PlaylistSelection? {
        val lines = playlist.lineSequence().map { it.trim() }.toList()
        val mediaByGroup = mutableMapOf<String, PlaylistSelection>()
        val alacMediaByGroup = mutableMapOf<String, PlaylistSelection>()
        val childPlaylists = mutableListOf<PlaylistSelection>()

        lines.forEach { line ->
            if (line.startsWith("#EXT-X-MEDIA:", ignoreCase = true)) {
                val attrs = parseAttributes(line.substringAfter(':'))
                val uri = attrs["URI"]?.takeIf { it.isNotBlank() } ?: return@forEach
                val groupId = attrs["GROUP-ID"].orEmpty()
                val selection = PlaylistSelection(resolveUri(baseUrl, uri), attrs.toQualityInfo())
                if (groupId.isNotBlank()) {
                    mediaByGroup[groupId] = selection
                    if (attrs.values.any { it.contains("alac", ignoreCase = true) || it.contains("lossless", ignoreCase = true) }) {
                        alacMediaByGroup[groupId] = selection
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
                if (attrs["CODECS"]?.contains("alac", ignoreCase = true) == true && uri != null) {
                    return PlaylistSelection(uri, streamQuality)
                }
                val audioGroup = attrs["AUDIO"]
                if (audioGroup != null) {
                    alacMediaByGroup[audioGroup]?.let { return it.withQuality(streamQuality) }
                    if (audioGroup.contains("alac", ignoreCase = true) || audioGroup.contains("lossless", ignoreCase = true)) {
                        mediaByGroup[audioGroup]?.let { return it.withQuality(streamQuality) }
                        if (uri != null) return PlaylistSelection(uri, streamQuality)
                    }
                }
            }
        }

        alacMediaByGroup.values.firstOrNull()?.let { return it }
        return childPlaylists.distinct().singleOrNull()
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

    private fun parseMediaPlaylist(baseUrl: String, playlist: String): MediaPlaylist {
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
                            if (resolved.isAlacDecryptKey() && resolved !in keyRing) {
                                keyRing += resolved
                            }
                            resolved.takeIf { it.isAlacDecryptKey() }
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
            durationMs = totalDurationMs.takeIf { sawExtinf && it > 0L }
        )
    }

    private fun decryptSegmentBytes(
        client: OkHttpClient,
        adamId: String,
        host: String,
        secure: Boolean,
        mode: AppleMusicWrapperManagerProvider.WrapperMode,
        playlist: MediaPlaylist,
        segment: MediaSegment,
        firstSampleIndex: Int,
    ): DecryptedSegment {
        val encryptedBytes = getEncryptedSegmentBytes(client, segment, consumeCached = true)
        val samples = parseFragmentSamples(encryptedBytes)
        segmentSampleCountCache[segmentCacheKey(segment)] = samples.size
        if (samples.isEmpty()) {
            return DecryptedSegment(encryptedBytes, 0)
        }

        val output = encryptedBytes.copyOf()
        val requestsByKey = samples.mapIndexed { localIndex, sample ->
            val globalIndex = firstSampleIndex + localIndex
            val key = selectKeyForSample(
                keyRing = playlist.keyRing,
                segmentKey = segment.keyUri,
                sampleDescriptionIndex = sample.sampleDescriptionIndex
            )
            val encryptedSample = encryptedBytes.copyOfRange(sample.offset, sample.offset + sample.size)
            SampleDecryptRequest(
                key = key,
                range = sample,
                request = AppleMusicWrapperManagerProvider.DecryptSample(globalIndex, encryptedSample)
            )
        }.groupBy { it.key }

        requestsByKey.forEach { (key, keyedRequests) ->
            val decryptSamples = keyedRequests.map { it.request }
            val decrypted = runCatching {
                AppleMusicWrapperManagerProvider.decryptSegment(
                    host = host,
                    secure = secure,
                    mode = mode,
                    adamId = adamId,
                    key = key,
                    samples = decryptSamples
                )
            }.recoverCatching { error ->
                if (error.message?.contains("did not return sample", ignoreCase = true) != true) throw error
                Thread.sleep(500L)
                AppleMusicWrapperManagerProvider.decryptSegment(
                    host = host,
                    secure = secure,
                    mode = mode,
                    adamId = adamId,
                    key = key,
                    samples = decryptSamples
                )
            }.getOrThrow()
            keyedRequests.forEach { sampleRequest ->
                val decryptedSample = decrypted[sampleRequest.request.sampleIndex]
                    ?: throw AppleMusicWrapperManagerProvider.WrapperManagerException(
                        "wrapper-manager did not return decrypted sample ${sampleRequest.request.sampleIndex}"
                    )
                if (decryptedSample.size != sampleRequest.range.size) {
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

        stripFragmentEncryptionBoxes(output)
        return DecryptedSegment(output, samples.size)
    }

    private fun resolveSegmentLengths(client: OkHttpClient, segments: List<MediaSegment>): List<Long>? {
        val lengths = mutableListOf<Long>()
        for (segment in segments) {
            val length = segment.range?.length
                ?: getSegmentContentLength(client, segment)
                ?: getEncryptedSegmentBytes(client, segment, consumeCached = false).size.toLong().takeIf { it > 0L }
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
            if (!consumeCached && bytes.size <= 8 * 1024 * 1024) {
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

    private fun String.isAlacDecryptKey(): Boolean {
        return endsWith(KEY_SUFFIX_ALAC) || endsWith(KEY_SUFFIX_DEFAULT)
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
        durationMs: Long?,
        averageBitrate: Long?,
    ): ByteArray {
        var output = initBytes.copyOf()
        patchSampleEntries(output, 0, output.size)
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

    private fun patchSampleEntries(data: ByteArray, start: Int, end: Int) {
        forEachChildBox(data, start, end) { box ->
            if (box.type == "stsd") {
                patchStsdEntries(data, box)
            }
            if (box.type in containerBoxes) {
                patchSampleEntries(data, box.payloadStart, box.end)
            }
        }
    }

    private fun patchStsdEntries(data: ByteArray, stsd: Mp4Box) {
        var pos = stsd.payloadStart + 8
        val entryCount = readUInt32(data, stsd.payloadStart + 4).toInt()
        repeat(entryCount) {
            val entry = readBox(data, pos, stsd.end) ?: return
            if (entry.type == "enca") {
                data[entry.start + 4] = 'a'.code.toByte()
                data[entry.start + 5] = 'l'.code.toByte()
                data[entry.start + 6] = 'a'.code.toByte()
                data[entry.start + 7] = 'c'.code.toByte()
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
            bandwidth = bandwidth ?: other.bandwidth
        ).takeIf { it.hasAny() }
    }

    private fun AlacQualityInfo.toMetadata(): AlacQualityMetadata? {
        return AlacQualityMetadata(
            bitrate = bandwidth
                ?.takeIf { it > 0L }
                ?.coerceAtMost(Int.MAX_VALUE.toLong())
                ?.toInt()
                ?: 0,
            sampleRate = sampleRate,
            bitDepth = bitDepth
        ).takeIf { it.hasAny() }
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

    private val containerBoxes = setOf(
        "moov", "trak", "mdia", "minf", "stbl", "edts", "moof", "traf", "mvex"
    )

    private val audioSampleEntryTypes = setOf(
        "alac", "enca", "mp4a"
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
                bandwidth?.let { "${(it / 1000L).coerceAtLeast(1L)}kbps" },
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

    private data class MediaPlaylist(
        val init: InitSegment,
        val segments: List<MediaSegment>,
        val keyRing: List<String>,
        val durationMs: Long?,
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
    )

    private data class SampleRange(
        val offset: Int,
        val size: Int,
        val sampleDescriptionIndex: Int,
    )

    private data class SampleDecryptRequest(
        val key: String,
        val range: SampleRange,
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

