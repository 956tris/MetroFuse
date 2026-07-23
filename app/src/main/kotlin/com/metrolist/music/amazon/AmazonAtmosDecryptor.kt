/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.amazon

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Decrypts Amazon Music Atmos (E-AC3) streams using FFmpeg.
 *
 * Atmos streams are delivered in encrypted MP4 containers. This decryptor
 * extracts the E-AC3 JOC stream and muxes it into a standard M4A container
 * that ExoPlayer can handle.
 */
object AmazonAtmosDecryptor {

    private const val TAG = "AmazonAtmosDecryptor"
    private const val CACHE_DIR_NAME = "amazon_atmos"
    private const val ENC_SUFFIX = ".enc.mp4"
    private const val DECRYPTED_SUFFIX = ".m4a"

    private val activeJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    suspend fun prepareStream(
        context: Context,
        resolved: AmazonAudioProvider.Resolved,
    ): String = withContext(Dispatchers.IO) {
        val asin = resolved.trackId
        val cached = getCachedM4a(asin)
        if (cached != null && cached.exists() && cached.length() > 0) {
            Timber.tag(TAG).d("Cache hit for Atmos ASIN $asin -> ${cached.absolutePath}")
            return@withContext cached.absolutePath
        }

        val existing = activeJobs[asin]
        if (existing != null && existing.isActive) {
            existing.join()
            val afterJoin = getCachedM4a(asin)
            if (afterJoin != null && afterJoin.exists() && afterJoin.length() > 0) {
                return@withContext afterJoin.absolutePath
            }
        }

        val key = resolved.decryptionKey
            ?: throw AmazonAudioProvider.AmazonResolutionException(
                "No decryption key for Atmos ASIN $asin",
            )

        try {
            downloadAndDecrypt(context, asin, resolved.mediaUri, key)
        } finally {
            activeJobs.remove(asin)
        }

        val output = getCachedM4a(asin)
            ?: throw AmazonAudioProvider.AmazonResolutionException(
                "FFmpeg produced no output file for Atmos ASIN $asin",
            )
        if (!output.exists() || output.length() == 0L) {
            throw AmazonAudioProvider.AmazonResolutionException(
                "FFmpeg output is empty for Atmos ASIN $asin",
            )
        }
        output.absolutePath
    }

    fun getCachedM4a(asin: String): File? {
        val dir = cachedCacheDir ?: return null
        val f = File(dir, "$asin$DECRYPTED_SUFFIX")
        return if (f.exists()) f else null
    }

    fun clearCache(asin: String) {
        getCachedM4a(asin)?.delete()
        cachedCacheDir?.let { File(it, "$asin$ENC_SUFFIX").delete() }
    }

    private fun downloadAndDecrypt(
        context: Context,
        asin: String,
        streamUrl: String,
        decryptionKey: String,
    ) {
        val workDir = ensureCacheDir(context)
        val encFile = File(workDir, "$asin$ENC_SUFFIX")
        val outFile = File(workDir, "$asin$DECRYPTED_SUFFIX")

        outFile.delete()

        if (!encFile.exists() || encFile.length() == 0L) {
            Timber.tag(TAG).d("Downloading encrypted Atmos stream for $asin")
            downloadToFile(streamUrl, encFile)
        }

        // Force 'mp4' format via -f to support eac3 even when the output extension is .m4a
        val command = buildString {
            append("-y ")
            append("-decryption_key ").append(decryptionKey).append(' ')
            append("-i \"").append(encFile.absolutePath).append("\" ")
            append("-c copy ")
            append("-vn ")
            append("-f mp4 ")
            append("\"").append(outFile.absolutePath).append("\"")
        }
        Timber.tag(TAG).d("Running Atmos FFmpeg: $command")

        val session = FFmpegKit.execute(command)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            val logs = session.allLogsAsString.orEmpty()
            Timber.tag(TAG).e("FFmpeg Atmos failed for $asin: $logs")
            outFile.delete()
            throw AmazonAudioProvider.AmazonResolutionException(
                "FFmpeg Atmos decryption failed for ASIN $asin (rc=${session.returnCode?.value})",
            )
        }

        encFile.delete()
    }

    private fun downloadToFile(url: String, dest: File) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", AmazonAudioProvider.BROWSER_USER_AGENT)
            .build()

        AmazonAudioProvider.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed: ${response.code}")
            val body = response.body ?: throw IOException("Empty body")
            dest.outputStream().use { output ->
                body.byteStream().use { input ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun ensureCacheDir(context: Context): File =
        File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }

    @Volatile
    private var cachedCacheDir: File? = null

    fun init(context: Context) {
        cachedCacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
    }
}
