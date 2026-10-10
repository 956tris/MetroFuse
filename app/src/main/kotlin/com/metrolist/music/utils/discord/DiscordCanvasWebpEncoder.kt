/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils.discord

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Pure-Kotlin animated WebP encoder (no native code).
 *
 * Each frame is compressed to a still lossy WebP with the framework encoder
 * ([Bitmap.compress], which uses libwebp internally). The VP8 image chunks are
 * then wrapped in an animated WebP container (RIFF + VP8X + ANIM + one ANMF per
 * frame), per https://developers.google.com/speed/webp/docs/riff_container
 *
 * Every frame is stored whole (no inter-frame diffing), so files are larger than
 * libwebp's WebPAnimEncoder / ffmpeg output, but colour is full 24-bit.
 *
 * Usage mirrors [DiscordCanvasGifEncoder]: [start], [addFrame] per frame, [finish].
 * Frames are buffered in memory because the RIFF header needs the total size.
 */
internal class DiscordCanvasWebpEncoder(
    private val width: Int,
    private val height: Int,
    private val frameDurationMs: Int,
    private val quality: Int = 70,
) {
    private val frames = ArrayList<ByteArray>()
    private var hasAlpha = false
    private var output: OutputStream? = null
    private var started = false

    /** Approximate size of the file if it were finished now. */
    val bufferedBytes: Long
        get() = frames.sumOf { it.size.toLong() + ANMF_OVERHEAD } + HEADER_OVERHEAD

    fun start(output: OutputStream) {
        require(width in 1..MAX_DIMENSION && height in 1..MAX_DIMENSION) { "Invalid WebP size" }
        this.output = output
        started = true
    }

    fun addFrame(bitmap: Bitmap) {
        check(started) { "WebP encoder must be started before adding frames" }
        check(bitmap.width == width && bitmap.height == height) {
            "Bitmap must match WebP dimensions"
        }

        val encoded = ByteArrayOutputStream(32 * 1024)
        val format =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
        check(bitmap.compress(format, quality.coerceIn(0, 100), encoded)) {
            "Bitmap.compress to WebP failed"
        }
        frames += extractFrameChunks(encoded.toByteArray())
    }

    fun finish() {
        if (!started) return
        val out = output ?: return
        check(frames.isNotEmpty()) { "No frames were added" }

        var riffPayloadSize = 4L // "WEBP"
        riffPayloadSize += 8 + VP8X_PAYLOAD // VP8X
        riffPayloadSize += 8 + ANIM_PAYLOAD // ANIM
        frames.forEach { riffPayloadSize += 8 + ANMF_FIXED_PAYLOAD + it.size }
        check(riffPayloadSize <= 0xFFFF_FFFFL - 8) { "WebP too large" }

        out.write(ascii("RIFF"))
        out.write(le32(riffPayloadSize))
        out.write(ascii("WEBP"))

        // VP8X: flags, 3 reserved bytes, canvas width-1, canvas height-1
        out.write(ascii("VP8X"))
        out.write(le32(VP8X_PAYLOAD.toLong()))
        out.write(VP8X_FLAG_ANIMATION or (if (hasAlpha) VP8X_FLAG_ALPHA else 0))
        out.write(byteArrayOf(0, 0, 0))
        out.write(le24(width - 1))
        out.write(le24(height - 1))

        // ANIM: background colour (BGRA), loop count (0 = forever)
        out.write(ascii("ANIM"))
        out.write(le32(ANIM_PAYLOAD.toLong()))
        out.write(byteArrayOf(0, 0, 0, 0))
        out.write(le16(0))

        frames.forEach { frameData ->
            out.write(ascii("ANMF"))
            out.write(le32((ANMF_FIXED_PAYLOAD + frameData.size).toLong()))
            out.write(le24(0)) // frame X / 2
            out.write(le24(0)) // frame Y / 2
            out.write(le24(width - 1))
            out.write(le24(height - 1))
            out.write(le24(frameDurationMs.coerceIn(1, 0xFF_FFFF)))
            out.write(ANMF_FLAG_NO_BLEND) // overwrite, no disposal
            out.write(frameData) // already padded to even length
        }
        out.flush()

        frames.clear()
        output = null
        started = false
    }

    /**
     * Pulls the ALPH / VP8 / VP8L chunks (with headers and padding) out of a
     * still WebP file so they can live inside an ANMF chunk. VP8X, ICCP, EXIF
     * and XMP chunks are dropped; they aren't allowed inside a frame.
     */
    private fun extractFrameChunks(file: ByteArray): ByteArray {
        check(
            file.size >= 12 &&
                file.startsWithAscii("RIFF", 0) &&
                file.startsWithAscii("WEBP", 8),
        ) { "Unexpected WebP encoder output" }

        val frame = ByteArrayOutputStream(file.size)
        var pos = 12
        while (pos + 8 <= file.size) {
            val length = readLe32(file, pos + 4)
            val padded = length + (length and 1)
            val end = (pos.toLong() + 8 + padded).coerceAtMost(file.size.toLong()).toInt()
            when {
                file.startsWithAscii("ALPH", pos) -> {
                    hasAlpha = true
                    frame.write(file, pos, end - pos)
                }
                file.startsWithAscii("VP8 ", pos) || file.startsWithAscii("VP8L", pos) -> {
                    frame.write(file, pos, end - pos)
                }
            }
            pos = end
        }
        check(frame.size() > 0) { "No image data in WebP encoder output" }

        // Keep every chunk even-sized in case the last one was cut short.
        if (frame.size() % 2 != 0) frame.write(0)
        return frame.toByteArray()
    }

    private fun ByteArray.startsWithAscii(tag: String, offset: Int): Boolean {
        if (offset + tag.length > size) return false
        return tag.indices.all { this[offset + it] == tag[it].code.toByte() }
    }

    private fun readLe32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)

    private fun le16(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value ushr 8) and 0xFF).toByte())

    private fun le24(value: Int): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
        )

    private fun le32(value: Long): ByteArray =
        byteArrayOf(
            (value and 0xFF).toByte(),
            ((value ushr 8) and 0xFF).toByte(),
            ((value ushr 16) and 0xFF).toByte(),
            ((value ushr 24) and 0xFF).toByte(),
        )

    private companion object {
        const val MAX_DIMENSION = 16_384
        const val VP8X_PAYLOAD = 10
        const val ANIM_PAYLOAD = 6
        const val ANMF_FIXED_PAYLOAD = 16
        const val ANMF_OVERHEAD = 8L + ANMF_FIXED_PAYLOAD
        const val HEADER_OVERHEAD = 12L + 8 + VP8X_PAYLOAD + 8 + ANIM_PAYLOAD
        const val VP8X_FLAG_ALPHA = 0x10
        const val VP8X_FLAG_ANIMATION = 0x02
        const val ANMF_FLAG_NO_BLEND = 0x02
    }
}
