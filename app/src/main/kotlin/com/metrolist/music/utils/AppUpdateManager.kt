package com.metrolist.music.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class UpdateDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val elapsedSeconds: Long,
) {
    val fraction: Float?
        get() = totalBytes.takeIf { it > 0L }?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }

    val remainingSeconds: Long?
        get() =
            if (totalBytes > downloadedBytes && bytesPerSecond > 0L) {
                (totalBytes - downloadedBytes + bytesPerSecond - 1L) / bytesPerSecond
            } else if (totalBytes in 1..downloadedBytes) {
                0L
            } else {
                null
            }
}

object AppUpdateManager {
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val PROGRESS_INTERVAL_MS = 250L
    private const val MAX_REDIRECTS = 5

    suspend fun downloadApk(
        context: Context,
        asset: ReleaseAsset,
        onProgress: suspend (UpdateDownloadProgress) -> Unit,
    ): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val updateDir = File(context.cacheDir, "app-updates").apply { mkdirs() }
                val safeName = asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "MetroFuse.apk" }
                val destination = File(updateDir, safeName)
                val temporary = File(updateDir, "$safeName.part")
                temporary.delete()

                var connection: HttpURLConnection? = null
                try {
                    connection = openFollowingRedirects(asset.downloadUrl)
                    val responseCode = connection.responseCode
                    if (responseCode !in 200..299) {
                        throw IOException("Update download failed with HTTP $responseCode")
                    }

                    val responseLength = connection.contentLengthLong.takeIf { it > 0L }
                    val totalBytes = responseLength ?: asset.size.takeIf { it > 0L } ?: -1L
                    val startedAt = SystemClock.elapsedRealtime()
                    var lastProgressAt = startedAt
                    var downloadedBytes = 0L

                    BufferedInputStream(connection.inputStream).use { input ->
                        FileOutputStream(temporary).buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                downloadedBytes += read

                                val now = SystemClock.elapsedRealtime()
                                if (now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                                    onProgress(progress(downloadedBytes, totalBytes, startedAt, now))
                                    lastProgressAt = now
                                }
                            }
                        }
                    }

                    if (downloadedBytes <= 0L) throw IOException("Downloaded APK is empty")
                    if (totalBytes > 0L && downloadedBytes != totalBytes) {
                        throw IOException("Incomplete APK: $downloadedBytes of $totalBytes bytes")
                    }
                    if (!hasZipHeader(temporary)) throw IOException("Downloaded file is not an APK")

                    destination.delete()
                    if (!temporary.renameTo(destination)) {
                        temporary.copyTo(destination, overwrite = true)
                        temporary.delete()
                    }

                    val finishedAt = SystemClock.elapsedRealtime()
                    onProgress(progress(downloadedBytes, downloadedBytes, startedAt, finishedAt))
                    destination
                } finally {
                    connection?.disconnect()
                    if (temporary.exists()) temporary.delete()
                }
            }
        }

    fun canRequestPackageInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri(),
        )

    fun installApk(context: Context, apk: File): Result<Unit> =
        runCatching {
            require(apk.isFile && apk.length() > 0L) { "Downloaded APK is unavailable" }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", apk)
            val intent =
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
        }

    internal fun formatBytes(bytes: Long): String {
        val safeBytes = bytes.coerceAtLeast(0L)
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = safeBytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) "$safeBytes ${units[unit]}" else "%.1f %s".format(java.util.Locale.US, value, units[unit])
    }

    internal fun formatDuration(seconds: Long?): String =
        seconds?.coerceAtLeast(0L)?.let { "%02d:%02d".format(it / 60L, it % 60L) } ?: "--:--"

    private fun progress(downloaded: Long, total: Long, startedAt: Long, now: Long): UpdateDownloadProgress {
        val elapsedMillis = (now - startedAt).coerceAtLeast(1L)
        return UpdateDownloadProgress(
            downloadedBytes = downloaded,
            totalBytes = total,
            bytesPerSecond = (downloaded * 1000L / elapsedMillis).coerceAtLeast(0L),
            elapsedSeconds = elapsedMillis / 1000L,
        )
    }

    private fun openFollowingRedirects(initialUrl: String): HttpURLConnection {
        var url = URL(initialUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val connection =
                (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream")
                    setRequestProperty("User-Agent", "MetroFuse-Updater")
                }
            when (connection.responseCode) {
                HttpURLConnection.HTTP_MOVED_PERM,
                HttpURLConnection.HTTP_MOVED_TEMP,
                HttpURLConnection.HTTP_SEE_OTHER,
                307,
                308,
                -> {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Update redirect has no destination")
                    connection.disconnect()
                    if (redirectCount == MAX_REDIRECTS) throw IOException("Too many update redirects")
                    url = URL(url, location)
                }

                else -> return connection
            }
        }
        throw IOException("Too many update redirects")
    }

    private fun hasZipHeader(file: File): Boolean =
        file.inputStream().use { input -> input.read() == 'P'.code && input.read() == 'K'.code }
}
