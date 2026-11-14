// Metrolist Project (C) 2026, GPL-3.0. See SOURCE.
package dev.metrofuse.jiosaavn

import dev.metrofuse.api.AudioResult
import dev.metrofuse.api.ProviderError
import dev.metrofuse.api.QualityPreference
import dev.metrofuse.api.QualityPreset
import dev.metrofuse.api.StreamDescriptor
import dev.metrofuse.api.StreamTransport
import kotlinx.serialization.json.JsonObject
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

internal object JioMedia {
    private val cdnUrl = Regex("https://(?:[A-Za-z0-9-]+\\.)*saavncdn\\.com/[A-Za-z0-9_./%?=&+~-]+", RegexOption.IGNORE_CASE)
    private val previewUrl = Regex("https://jiotunepreview\\.jio\\.com/[A-Za-z0-9_./%?=&+-]+")
    private val base64 = Regex("[A-Za-z0-9+/]+={0,2}")
    private val marker = Regex("_(12|48|96|160|320)\\.(mp3|mp4)(?=$|[?])", RegexOption.IGNORE_CASE)

    fun decrypt(encrypted: String?): String? {
        val text = encrypted?.replace(Regex("\\s"), "") ?: return null
        if (text.length !in 1..4096 || text.length % 4 != 0 || !base64.matches(text)) return null
        val plain =
            try {
                val bytes = decodeBase64(text)
                if (bytes.size % 8 != 0) return null
                val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("38346591".toByteArray(Charsets.US_ASCII), "DES"))
                cipher.doFinal(bytes)
            } catch (_: Exception) {
                return null
            }
        if (plain.any { it.toInt() !in 32..126 }) return null
        val url = String(plain, Charsets.US_ASCII).trim().replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
        return url.takeIf(cdnUrl::matches)
    }

    // java.util.Base64 is unavailable on the host's minimum Android API 24.
    private fun decodeBase64(text: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val padding = text.takeLast(2).count { it == '=' }
        val bytes = ByteArray(text.length / 4 * 3 - padding)
        var written = 0
        for (offset in text.indices step 4) {
            var word = 0
            for (index in offset until offset + 4) {
                word = (word shl 6) or if (text[index] == '=') 0 else alphabet.indexOf(text[index])
            }
            for (shift in listOf(16, 8, 0)) {
                if (written < bytes.size) bytes[written++] = (word ushr shift).toByte()
            }
        }
        return bytes
    }

    fun full(
        song: JsonObject?,
        quality: QualityPreference,
    ): AudioResult {
        val track = JioCatalog.song(song) ?: return error("TRACK_UNAVAILABLE")
        if (quality.lossless) return error("LOSSLESS_UNAVAILABLE")
        val more = requireNotNull(song).obj("more_info")
        val encrypted = song.text("encrypted_media_url") ?: more.text("encrypted_media_url") ?: return error("STREAM_UNAVAILABLE")
        val base = decrypt(encrypted) ?: return error("STREAM_DECODE_FAILED")
        val match = marker.find(base) ?: return error("STREAM_FORMAT_UNAVAILABLE")
        val has320 = (song.text("320kbps") ?: more.text("320kbps")).equals("true", ignoreCase = true)
        val preferred = if (quality.preset == QualityPreset.LOW) 96000 else 320000
        val ceiling = quality.maxBitrate?.coerceAtMost(preferred) ?: preferred
        val bitrate =
            listOf(320000, 160000, 96000, 48000, 12000).firstOrNull { it <= ceiling && (it != 320000 || has320) }
                ?: return error("QUALITY_UNAVAILABLE")
        val mp3 = match.groupValues[2].equals("mp3", ignoreCase = true)
        val extension = if (mp3) "mp3" else "mp4"
        return AudioResult(
            stream =
                StreamDescriptor(
                    transport = StreamTransport.DIRECT,
                    locator = marker.replace(base, "_${bitrate / 1000}.$extension"),
                    mimeType = if (mp3) "audio/mpeg" else "audio/mp4",
                    codec = if (mp3) "mp3" else "mp4a.40.2",
                    bitrate = bitrate,
                    durationSeconds = track.duration.takeIf { it > 0 }?.div(1000),
                    attributes = mapOf("provider" to "jiosaavn", "content" to "full song", "trackId" to track.id),
                ),
        )
    }

    fun resolve(
        song: JsonObject?,
        quality: QualityPreference,
        previews: Boolean,
    ): AudioResult {
        val full = full(song, quality)
        if (full.stream != null || quality.lossless || full.error?.code == "QUALITY_UNAVAILABLE" || !previews) return full
        val track = JioCatalog.song(song) ?: return full
        val url = song?.obj("more_info")?.text("vlink")?.takeIf(previewUrl::matches) ?: return full
        return AudioResult(
            stream =
                StreamDescriptor(
                    StreamTransport.DIRECT,
                    url,
                    attributes = mapOf("provider" to "jiosaavn", "content" to "public JioTune preview excerpt", "trackId" to track.id),
                ),
        )
    }

    private fun error(code: String) = AudioResult(error = ProviderError(code))
}
