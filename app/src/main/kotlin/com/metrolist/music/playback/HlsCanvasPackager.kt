/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Shared HLS canvas downloader/packager.
 *
 * Apple motion canvases are HLS (m3u8) playlists, not direct video files. To
 * embed one in a downloaded track (or keep it for offline playback) every
 * referenced segment/init/key has to be fetched and stored alongside a
 * rewritten manifest. Used by [PublicDownloadExporter] at export time and by
 * [MusicService] when prefetching streaming canvases for offline reuse.
 *
 * A failed segment fetch aborts the whole package (returns null) instead of
 * writing a manifest with missing entries — a partial manifest still parses,
 * so the player reports a canvas URL that renders as gaps/black frames and
 * the UI hides the artwork for nothing.
 */
internal object HlsCanvasPackager {
    private const val TAG = "HlsCanvasPackager"

    fun downloadAndPackage(
        playlistUrl: String,
        playlist: String,
        headers: Map<String, String>,
        client: OkHttpClient,
        maxBytes: Int = MAX_PACKAGE_BYTES,
    ): ByteArray? {
        val baseUrl = playlistUrl.toHttpUrlOrNull() ?: return null
        val mediaPlaylistUrl = selectVariant(baseUrl, playlist)
        val mediaBaseUrl = mediaPlaylistUrl ?: baseUrl
        val mediaPlaylist =
            if (mediaPlaylistUrl == null) {
                playlist
            } else {
                fetchText(mediaPlaylistUrl.toString(), headers, client) ?: return null
            }
        if (!mediaPlaylist.contains("#EXTINF")) return null
        return packagePlaylist(mediaBaseUrl, mediaPlaylist, headers, client, maxBytes)
    }

    fun packagePlaylist(
        baseUrl: HttpUrl,
        playlist: String,
        headers: Map<String, String>,
        client: OkHttpClient,
        maxBytes: Int = MAX_PACKAGE_BYTES,
    ): ByteArray? =
        runCatching {
            val output = ByteArrayOutputStream()
            var unpackedBytes = 0L
            var segmentIndex = 0
            var initIndex = 0
            var keyIndex = 0
            val rewrittenLines = mutableListOf<String>()

            ZipOutputStream(output).use { zip ->
                fun addEntry(
                    name: String,
                    bytes: ByteArray,
                ) {
                    unpackedBytes += bytes.size
                    if (unpackedBytes > maxBytes) error("Canvas HLS package is too large")
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }

                for (rawLine in playlist.lineSequence()) {
                    val line = rawLine.trim()
                    when {
                        line.startsWith("#EXT-X-MAP", ignoreCase = true) -> {
                            val uri = URI_ATTRIBUTE.find(line)?.groupValues?.getOrNull(1)
                            val resolved = uri?.let { baseUrl.resolve(it) }
                                ?: error("Unresolvable EXT-X-MAP uri in canvas playlist")
                            val bytes = fetchBytes(resolved.toString(), headers, client, maxBytes)
                                ?: error("Failed to fetch canvas init segment")
                            val entryName = "init_${initIndex++}.${extension(resolved, "mp4")}"
                            addEntry(entryName, bytes)
                            rewrittenLines += URI_ATTRIBUTE.replace(line, "URI=\"$entryName\"")
                        }
                        line.startsWith("#EXT-X-KEY", ignoreCase = true) -> {
                            val uri = URI_ATTRIBUTE.find(line)?.groupValues?.getOrNull(1)
                            val resolved = uri?.let { baseUrl.resolve(it) }
                            if (resolved != null) {
                                val bytes = fetchBytes(resolved.toString(), headers, client, maxBytes)
                                    ?: error("Failed to fetch canvas key")
                                val entryName = "key_${keyIndex++}.key"
                                addEntry(entryName, bytes)
                                rewrittenLines += URI_ATTRIBUTE.replace(line, "URI=\"$entryName\"")
                            } else {
                                // Key without a URI (e.g. NONE/SAMPLE-AES intrinsic)
                                // needs no download; keep the line as-is.
                                rewrittenLines += line
                            }
                        }
                        line.isBlank() || line.startsWith("#") -> rewrittenLines += line
                        else -> {
                            val resolved = baseUrl.resolve(line)
                                ?: error("Unresolvable canvas segment uri: $line")
                            val bytes = fetchBytes(resolved.toString(), headers, client, maxBytes)
                                ?: error("Failed to fetch canvas segment")
                            val entryName = "segment_${segmentIndex++}.${extension(resolved, "m4s")}"
                            addEntry(entryName, bytes)
                            rewrittenLines += entryName
                        }
                    }
                }

                if (segmentIndex == 0) error("Canvas playlist contains no segments")
                val manifest = rewrittenLines.joinToString("\n").toByteArray(Charsets.UTF_8)
                addEntry(MANIFEST_NAME, manifest)
            }

            output.toByteArray().takeIf { it.size <= maxBytes }
        }.getOrElse { error ->
            Timber.tag(TAG).d(error, "Failed to package Canvas HLS")
            null
        }

    internal fun selectVariant(
        baseUrl: HttpUrl,
        playlist: String,
    ): HttpUrl? {
        val variants = mutableListOf<Pair<Int, HttpUrl>>()
        var pendingBandwidth: Int? = null
        playlist.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) -> {
                    pendingBandwidth = BANDWIDTH_ATTRIBUTE.find(line)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                }
                pendingBandwidth != null && line.isNotBlank() && !line.startsWith("#") -> {
                    baseUrl.resolve(line)?.let { variants += (pendingBandwidth ?: Int.MAX_VALUE) to it }
                    pendingBandwidth = null
                }
            }
        }
        return variants.minByOrNull { it.first }?.second
    }

    private fun fetchText(
        url: String,
        headers: Map<String, String>,
        client: OkHttpClient,
    ): String? =
        runCatching {
            client.newCall(request(url, headers)).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body.string()
            }
        }.getOrNull()

    private fun fetchBytes(
        url: String,
        headers: Map<String, String>,
        client: OkHttpClient,
        maxBytes: Int,
    ): ByteArray? =
        runCatching {
            client.newCall(request(url, headers)).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body
                if (body.contentLength() > maxBytes) return@use null
                body.bytes().takeIf { it.size <= maxBytes }
            }
        }.getOrNull()

    private fun request(
        url: String,
        headers: Map<String, String>,
    ): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0")
            .apply {
                headers.forEach { (name, value) ->
                    if (name.isNotBlank() && value.isNotBlank()) {
                        header(name, value)
                    }
                }
            }
            .build()

    private fun extension(
        url: HttpUrl,
        fallback: String,
    ): String {
        val segment = url.pathSegments.lastOrNull().orEmpty().substringBefore("?")
        val ext = segment.substringAfterLast('.', "")
        return ext
            .takeIf { it.isNotBlank() && it.length <= 5 && it.all { char -> char.isLetterOrDigit() } }
            ?: fallback
    }

    internal const val MANIFEST_NAME = "manifest.m3u8"
    internal const val MAX_PACKAGE_BYTES = 8 * 1024 * 1024
    private val URI_ATTRIBUTE = Regex("""URI="([^"]+)"""")
    private val BANDWIDTH_ATTRIBUTE = Regex("""BANDWIDTH=(\d+)""")
}
