/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import androidx.core.net.toUri
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import timber.log.Timber

/**
 * Disk cache for remote Canvas videos so they keep playing offline.
 *
 * Why this exists (issue #88 + #66):
 * - Spotify/Apple canvas URLs were only held in in-memory maps. After a
 *   restart or when offline, resolving them hits the network and fails,
 *   so the player shows nothing even though the song itself is cached.
 * - Downloaded songs only kept canvas when embed mode was enabled
 *   (default OFF since 5.1), so most users never got offline canvas.
 *
 * This store keeps the raw canvas bytes per song id under
 * `files/canvas-offline/`. Playback prefers:
 * embedded-in-file -> offline disk cache -> network.
 */
internal object CanvasOfflineCache {
    private const val TAG = "CanvasOfflineCache"
    private const val DIR_NAME = "canvas-offline"
    private const val HLS_MANIFEST_NAME = "manifest.m3u8"
    private const val MAX_CACHE_BYTES = 200L * 1024L * 1024L

    fun cachedUriFor(
        context: Context,
        songId: String,
    ): String? {
        if (songId.isBlank()) return null
        val digest = stableDigest(songId)
        val dir = offlineDir(context)

        // Plain video fast path.
        listOf("mp4", "webm", "mov").forEach { ext ->
            dir.resolve("$digest.$ext").takeIf { it.exists() && it.length() > 0 }?.let {
                touch(it)
                return it.toUri().toString()
            }
        }

        // HLS package path (stored as zip, extracted on demand).
        val zip = dir.resolve("$digest.m3u8.zip")
        if (zip.exists() && zip.length() > 0) {
            touch(zip)
            return extractHlsPackage(dir, digest, zip.readBytes()) ?: zip.toUri().toString()
        }
        return null
    }

    fun put(
        context: Context,
        songId: String,
        canvas: EmbeddedCanvas,
        sourceUrl: String? = null,
    ) {
        if (songId.isBlank() || canvas.bytes.isEmpty()) return
        runCatching {
            val digest = stableDigest(songId)
            val dir = offlineDir(context).apply { mkdirs() }
            val ext =
                when (canvas.mimeType.lowercase(Locale.US)) {
                    AudioTagWriter.METROFUSE_HLS_CANVAS_MIME -> "m3u8.zip"
                    "video/webm" -> "webm"
                    "video/quicktime" -> "mov"
                    else -> "mp4"
                }
            // Remove stale variants for the same song so only one file lives per id.
            listOf("mp4", "webm", "mov", "m3u8.zip").forEach { oldExt ->
                if (oldExt != ext) dir.resolve("$digest.$oldExt").delete()
            }
            val target = dir.resolve("$digest.$ext")
            if (target.exists() && target.length() == canvas.bytes.size.toLong()) {
                touch(target)
                return
            }
            target.outputStream().use { it.write(canvas.bytes) }
            if (ext == "m3u8.zip") {
                // Drop the previous extraction so a refreshed package can't
                // keep serving stale segments from the old zip.
                dir.resolve("${digest}_hls").deleteRecursively()
            }
            dir.resolve("$digest.meta").writeText(
                listOf(canvas.provider, canvas.mimeType, sourceUrl.orEmpty(), System.currentTimeMillis().toString())
                    .joinToString("\n"),
                Charsets.UTF_8,
            )
            evictIfNeeded(dir)
        }.onFailure { error ->
            Timber.tag(TAG).d(error, "Failed to cache canvas for $songId")
        }
    }

    private fun offlineDir(context: Context): File = File(context.filesDir, DIR_NAME)

    private fun extractHlsPackage(
        dir: File,
        digest: String,
        bytes: ByteArray,
    ): String? =
        runCatching {
            val targetDir = dir.resolve("${digest}_hls").apply { mkdirs() }
            val manifest = targetDir.resolve(HLS_MANIFEST_NAME)
            // Reuse extraction if manifest already exists and zip is unchanged.
            if (manifest.exists()) return@runCatching manifest.toUri().toString()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || entry.name.contains("..")) {
                        zip.closeEntry()
                        continue
                    }
                    val outFile = targetDir.resolve(entry.name)
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output -> zip.copyTo(output) }
                    zip.closeEntry()
                }
            }
            // Only advertise a manifest that actually parses as HLS with
            // segments. A partial/corrupt zip must not produce a canvas URL
            // that hides the artwork and renders nothing.
            manifest.takeIf { it.exists() && it.isValidHlsManifest() }?.toUri()?.toString()
        }.getOrElse { error ->
            Timber.tag(TAG).d(error, "Failed to extract cached HLS canvas")
            null
        }

    private fun evictIfNeeded(dir: File) {
        runCatching {
            val files = dir.listFiles()?.filter { it.isFile && it.extension != "meta" } ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_CACHE_BYTES) return
            files.sortedBy { it.lastModified() }.forEach { file ->
                if (total <= MAX_CACHE_BYTES) return@forEach
                total -= file.length()
                file.delete()
                dir.resolve("${file.nameWithoutExtension}.meta").delete()
                dir.resolve("${file.nameWithoutExtension}_hls").deleteRecursively()
            }
        }
    }

    private fun touch(file: File) {
        file.setLastModified(System.currentTimeMillis())
    }

    private fun File.isValidHlsManifest(): Boolean =
        runCatching {
            if (!exists() || length() <= 0L || length() > 256 * 1024L) return false
            val text = readText(Charsets.UTF_8)
            text.contains("#EXTM3U") &&
                (text.contains("#EXTINF") || text.contains("#EXT-X-STREAM-INF"))
        }.getOrDefault(false)

    private fun stableDigest(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
