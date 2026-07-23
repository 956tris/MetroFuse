package com.metrolist.music.utils.discord

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.math.min
import kotlin.math.roundToInt

object DiscordCanvasLocalConverter {
    private const val TAG = "DiscordCanvasLocal"
    private const val SIZE_PX = 320
    private const val FPS = 15
    private const val DURATION_MS = 4_000L
    private const val MAX_UPLOAD_BYTES = 10L * 1024L * 1024L
    private const val MAX_MATERIALIZED_BYTES = 8L * 1024L * 1024L
    private val bandwidthRegex = Regex("""BANDWIDTH=(\d+)""")
    private val codecRegex = Regex("CODECS=\"([^\"]+)\"")
    private val hlsMapRegex = Regex("""#EXT-X-MAP:.*?URI="([^"]+)".*?(?:BYTERANGE="(\d+)(?:@(\d+))?")?""")
    private val hlsByteRangeRegex = Regex("""#EXT-X-BYTERANGE:(\d+)(?:@(\d+))?""")
    private val hlsDurationRegex = Regex("""#EXTINF:([0-9.]+)""")
    private val gifMediaType = "image/gif".toMediaType()

    suspend fun prepareAndUpload(
        context: Context,
        canvasUrl: String,
        headers: Map<String, String> = emptyMap(),
        uploadUrl: String,
        client: OkHttpClient,
    ): String? = withContext(Dispatchers.IO) {
        val gifFile = cachedGifFile(context, canvasUrl)
        if (!gifFile.exists() || gifFile.length() == 0L) {
            runCatching {
                convertToGif(canvasUrl, headers, client, gifFile)
            }.onFailure { error ->
                gifFile.delete()
                Timber.tag(TAG).w(error, "Apple canvas local conversion failed")
            }.getOrNull() ?: return@withContext null
        }

        if (gifFile.length() <= 0L || gifFile.length() > MAX_UPLOAD_BYTES) {
            Timber.tag(TAG).w("Apple canvas GIF is too large for upload: ${gifFile.length()} bytes")
            gifFile.delete()
            return@withContext null
        }

        runCatching {
            val request = Request.Builder()
                .url(uploadUrl)
                .post(gifFile.asRequestBody(gifMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).w("Apple canvas GIF upload failed: HTTP ${response.code}")
                    return@use null
                }
                JSONObject(response.body.string())
                    .optString("url")
                    .takeIf { it.endsWith(".gif", ignoreCase = true) }
            }
        }.onFailure { error ->
            Timber.tag(TAG).w(error, "Apple canvas GIF upload request failed")
        }.getOrNull()
    }

    fun cacheKey(canvasUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("gif-fmp4-v3|$SIZE_PX|$FPS|$DURATION_MS|$canvasUrl".toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun cachedGifFile(context: Context, canvasUrl: String): File {
        val cacheDir = File(context.cacheDir, "discord-apple-canvas")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        return File(cacheDir, "${cacheKey(canvasUrl)}.gif")
    }

    private fun convertToGif(
        canvasUrl: String,
        headers: Map<String, String>,
        client: OkHttpClient,
        outputFile: File,
    ) {
        val tempFile = File(outputFile.parentFile, "${outputFile.name}.tmp")
        val localVideoFile = File(outputFile.parentFile, "${outputFile.name}.source.mp4")
        tempFile.delete()
        localVideoFile.delete()

        try {
            val sources = buildCanvasSources(canvasUrl, headers, client, localVideoFile)
            var lastFailure: Throwable? = null
            sources.forEach { source ->
                tempFile.delete()
                runCatching {
                    renderGifFromSource(source, headers, tempFile)
                    if (!tempFile.renameTo(outputFile)) {
                        throw IllegalStateException("Could not store Apple canvas GIF")
                    }
                    return
                }.onFailure { error ->
                    lastFailure = error
                    Timber.tag(TAG).d(error, "Apple canvas source failed: ${source.label}")
                }
            }
            throw IllegalStateException("No Apple canvas source could be decoded", lastFailure)
        } finally {
            tempFile.delete()
            localVideoFile.delete()
        }
    }

    private fun buildCanvasSources(
        canvasUrl: String,
        headers: Map<String, String>,
        client: OkHttpClient,
        localVideoFile: File,
    ): List<CanvasSource> {
        val sources = mutableListOf<CanvasSource>()
        if (canvasUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true)) {
            val masterUrl = canvasUrl.toHttpUrlOrNull()
            val masterPlaylist = fetchText(client, canvasUrl, headers)
            val mediaPlaylistUrl =
                if (masterUrl != null && masterPlaylist != null) {
                    selectMediaPlaylist(masterUrl, masterPlaylist)
                } else {
                    null
                }

            materializeHlsToLocalMp4(
                canvasUrl = canvasUrl,
                headers = headers,
                client = client,
                outputFile = localVideoFile,
                masterUrl = masterUrl,
                masterPlaylist = masterPlaylist,
                mediaPlaylistUrl = mediaPlaylistUrl,
            )?.let { sourcePath ->
                sources += CanvasSource.Local(sourcePath)
            }

            mediaPlaylistUrl
                ?.toString()
                ?.takeIf { !it.equals(canvasUrl, ignoreCase = true) }
                ?.let { sources += CanvasSource.Remote("selected HLS media playlist", it) }
        }

        sources += CanvasSource.Remote("original canvas URL", canvasUrl)
        return sources
    }

    private fun renderGifFromSource(
        source: CanvasSource,
        headers: Map<String, String>,
        outputFile: File,
    ) {
        val retriever = MediaMetadataRetriever()
        val frame = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        try {
            when (source) {
                is CanvasSource.Local -> retriever.setDataSource(source.path)
                is CanvasSource.Remote -> retriever.setDataSource(
                    source.url,
                    buildFrameRequestHeaders(headers),
                )
            }

            val durationUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?.times(1_000L)
            val frameCount = ((DURATION_MS / 1_000f) * FPS).roundToInt().coerceAtLeast(1)
            val delayCentiseconds = (100f / FPS.toFloat()).roundToInt().coerceAtLeast(1)
            var firstFrameFingerprint: Long? = null
            var changedFrameCount = 0
            outputFile.outputStream().buffered().use { stream ->
                val encoder = DiscordCanvasGifEncoder(SIZE_PX, SIZE_PX, delayCentiseconds)
                encoder.start(stream)
                repeat(frameCount) { index ->
                    val requestedUs = ((index * 1_000_000L) / FPS)
                        .let { if (durationUs != null) it % durationUs else it }
                    val sourceFrame = retriever.getFrameAtTime(
                        requestedUs,
                        MediaMetadataRetriever.OPTION_CLOSEST,
                    ) ?: throw IllegalStateException("Could not decode Apple canvas frame")
                    canvas.drawFrame(sourceFrame, frame)
                    val fingerprint = frame.fingerprint()
                    val firstFingerprint = firstFrameFingerprint
                    if (firstFingerprint == null) {
                        firstFrameFingerprint = fingerprint
                    } else if (fingerprint != firstFingerprint) {
                        changedFrameCount++
                    }
                    encoder.addFrame(frame)
                    sourceFrame.recycle()
                }
                encoder.finish()
            }
            if (outputFile.length() <= 0L) {
                throw IllegalStateException("Apple canvas GIF encoder produced an empty file")
            }
            if (changedFrameCount == 0) {
                outputFile.delete()
                throw IllegalStateException("Apple canvas decoder returned only static frames")
            }
        } finally {
            runCatching { retriever.release() }
            frame.recycle()
        }
    }

    private fun materializeHlsToLocalMp4(
        canvasUrl: String,
        headers: Map<String, String>,
        client: OkHttpClient,
        outputFile: File,
        masterUrl: HttpUrl? = null,
        masterPlaylist: String? = null,
        mediaPlaylistUrl: HttpUrl? = null,
    ): String? {
        if (!canvasUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true)) return null
        val resolvedMasterUrl = masterUrl ?: canvasUrl.toHttpUrlOrNull() ?: return null
        val resolvedMasterPlaylist = masterPlaylist ?: fetchText(client, canvasUrl, headers) ?: return null
        val resolvedMediaPlaylistUrl = mediaPlaylistUrl ?: selectMediaPlaylist(resolvedMasterUrl, resolvedMasterPlaylist) ?: resolvedMasterUrl
        val mediaPlaylist =
            if (resolvedMediaPlaylistUrl == resolvedMasterUrl) {
                resolvedMasterPlaylist
            } else {
                fetchText(client, resolvedMediaPlaylistUrl.toString(), headers) ?: return null
            }
        if (!mediaPlaylist.contains("#EXTINF")) return null

        outputFile.outputStream().buffered().use { output ->
            writeHlsFragment(
                client = client,
                baseUrl = resolvedMediaPlaylistUrl,
                playlist = mediaPlaylist,
                headers = headers,
                output = output,
            )
        }
        return outputFile.takeIf { it.length() > 0L }?.absolutePath
    }

    private fun selectMediaPlaylist(
        baseUrl: HttpUrl,
        playlist: String,
    ): HttpUrl? {
        val variants = mutableListOf<HlsVariant>()
        var pendingBandwidth: Int? = null
        var pendingCodecs: String? = null
        playlist.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) -> {
                    pendingBandwidth = bandwidthRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    pendingCodecs = codecRegex.find(line)?.groupValues?.getOrNull(1)
                }
                pendingBandwidth != null && line.isNotBlank() && !line.startsWith("#") -> {
                    baseUrl.resolve(line)?.let { url ->
                        variants += HlsVariant(
                            url = url,
                            bandwidth = pendingBandwidth ?: Int.MAX_VALUE,
                            codecs = pendingCodecs.orEmpty(),
                        )
                    }
                    pendingBandwidth = null
                    pendingCodecs = null
                }
            }
        }

        return variants
            .filter { it.codecs.contains("avc1", ignoreCase = true) }
            .ifEmpty { variants }
            .minByOrNull { it.bandwidth }
            ?.url
    }

    private fun writeHlsFragment(
        client: OkHttpClient,
        baseUrl: HttpUrl,
        playlist: String,
        headers: Map<String, String>,
        output: OutputStream,
    ) {
        val nextOffsetByUrl = mutableMapOf<String, Long>()
        var pendingRange: ByteRange? = null
        var pendingDurationSeconds = 0.0
        var writtenBytes = 0L
        var writtenDurationSeconds = 0.0

        hlsMapRegex.find(playlist)?.let { match ->
            val uri = match.groupValues.getOrNull(1).orEmpty()
            val length = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toLongOrNull()
            val offset = match.groupValues.getOrNull(3)?.takeIf { it.isNotBlank() }?.toLongOrNull()
            val resolved = baseUrl.resolve(uri)
            if (resolved != null) {
                writtenBytes += fetchRangeBytes(
                    client = client,
                    url = resolved,
                    headers = headers,
                    range = length?.let { ByteRange(length = it, offset = offset ?: 0L) },
                    nextOffsetByUrl = nextOffsetByUrl,
                    output = output,
                    currentBytes = writtenBytes,
                )
            }
        }

        playlist.lineSequence().forEach { rawLine ->
            if (writtenDurationSeconds >= DURATION_MS / 1_000.0) return
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingDurationSeconds = hlsDurationRegex.find(line)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toDoubleOrNull()
                        ?: 0.0
                }
                line.startsWith("#EXT-X-BYTERANGE", ignoreCase = true) -> {
                    val match = hlsByteRangeRegex.find(line)
                    val length = match?.groupValues?.getOrNull(1)?.toLongOrNull()
                    val offset = match?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }?.toLongOrNull()
                    pendingRange = length?.let { ByteRange(length = it, offset = offset) }
                }
                line.isNotBlank() && !line.startsWith("#") -> {
                    val resolved = baseUrl.resolve(line)
                    if (resolved != null) {
                        writtenBytes += fetchRangeBytes(
                            client = client,
                            url = resolved,
                            headers = headers,
                            range = pendingRange,
                            nextOffsetByUrl = nextOffsetByUrl,
                            output = output,
                            currentBytes = writtenBytes,
                        )
                        writtenDurationSeconds += pendingDurationSeconds
                    }
                    pendingRange = null
                    pendingDurationSeconds = 0.0
                }
            }
        }
    }

    private fun fetchRangeBytes(
        client: OkHttpClient,
        url: HttpUrl,
        headers: Map<String, String>,
        range: ByteRange?,
        nextOffsetByUrl: MutableMap<String, Long>,
        output: OutputStream,
        currentBytes: Long,
    ): Long {
        val rangeStart =
            range?.offset
                ?: range?.let { nextOffsetByUrl[url.toString()] ?: 0L }
        val rangeLength = range?.length
        val rangeEnd = rangeStart?.let { start -> rangeLength?.let { start + it - 1L } }
        val request = canvasRequest(url.toString(), headers, rangeStart, rangeEnd)
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Could not fetch Apple canvas segment: HTTP ${response.code}")
            val bytes = response.body.bytes()
            val nextSize = currentBytes + bytes.size
            if (nextSize > MAX_MATERIALIZED_BYTES) error("Apple canvas materialized video is too large")
            output.write(bytes)
            if (rangeStart != null) {
                nextOffsetByUrl[url.toString()] = rangeStart + bytes.size
            }
            return bytes.size.toLong()
        }
    }

    private fun fetchText(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
    ): String? =
        client.newCall(canvasRequest(url, headers)).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body.string()
        }

    private fun canvasRequest(
        url: String,
        headers: Map<String, String>,
        rangeStart: Long? = null,
        rangeEnd: Long? = null,
    ): Request =
        Request.Builder()
            .url(url)
            .apply {
                buildFrameRequestHeaders(headers).forEach { (name, value) -> header(name, value) }
                if (rangeStart != null && rangeEnd != null) {
                    header("Range", "bytes=$rangeStart-$rangeEnd")
                }
            }
            .build()

    private fun Canvas.drawFrame(source: Bitmap, target: Bitmap) {
        target.eraseColor(Color.BLACK)
        val scale = min(
            SIZE_PX.toFloat() / source.width.toFloat(),
            SIZE_PX.toFloat() / source.height.toFloat(),
        )
        val drawWidth = source.width * scale
        val drawHeight = source.height * scale
        val left = (SIZE_PX - drawWidth) / 2f
        val top = (SIZE_PX - drawHeight) / 2f
        drawBitmap(
            source,
            Rect(0, 0, source.width, source.height),
            RectF(left, top, left + drawWidth, top + drawHeight),
            null,
        )
    }

    private fun Bitmap.fingerprint(): Long {
        var hash = 1125899906842597L
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                hash = (hash * 31) + getPixel(x, y)
                x += 16
            }
            y += 16
        }
        return hash
    }

    private fun buildFrameRequestHeaders(headers: Map<String, String>): Map<String, String> {
        val merged = linkedMapOf("User-Agent" to "Mozilla/5.0")
        headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank()) {
                merged[name] = value
            }
        }
        return merged
    }

    private data class HlsVariant(
        val url: HttpUrl,
        val bandwidth: Int,
        val codecs: String,
    )

    private data class ByteRange(
        val length: Long?,
        val offset: Long?,
    )

    private sealed class CanvasSource(open val label: String) {
        data class Local(val path: String) : CanvasSource("local fMP4 fragment")
        data class Remote(
            override val label: String,
            val url: String,
        ) : CanvasSource(label)
    }
}
