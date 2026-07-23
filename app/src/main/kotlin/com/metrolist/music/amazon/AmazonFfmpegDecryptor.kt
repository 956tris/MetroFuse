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
 * Decrypts Amazon Music CMAF/MP4 streams using FFmpeg's built-in CENC support.
 *
 * Amazon wraps FLAC audio inside an encrypted MP4 container using Common Encryption
 * (CENC). The `-decryption_key` flag is FFmpeg's native path for handling this — it
 * parses the `moov`/`moof`/`traf`/`tenc` boxes, derives the correct per-fragment IVs,
 * and handles both `cenc` (AES-128-CTR) and `cbcs` (AES-128-CBC pattern) schemes.
 * Replicating that in pure Kotlin is impractical, so we delegate to FFmpeg.
 *
 * Flow per track:
 *  1. Download the encrypted stream from the CDN to a temp `.enc.mp4` file.
 *  2. Run `ffmpeg -y -decryption_key <hex> -i <enc.mp4> -c copy <out.flac>`.
 *  3. Feed the resulting clear FLAC to ExoPlayer via a `file://` URI.
 *
 * Decrypted files are cached under `cacheDir/amazon_flac/<asin>.flac` so subsequent
 * plays of the same track are instant and incur no network or FFmpeg cost.
 */
object AmazonFfmpegDecryptor {

    private const val TAG = "AmazonFfmpegDecryptor"
    private const val CACHE_DIR_NAME = "amazon_flac"
    private const val ENC_SUFFIX = ".enc.mp4"
    private const val FLAC_SUFFIX = ".flac"

    private val activeJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    /**
     * Ensures a decrypted FLAC exists for [resolved] and returns its absolute path.
     *
     * If a cached decrypt is already on disk it is returned immediately. If a
     * decryption for the same ASIN is already in flight, this call awaits it
     * rather than launching a duplicate. Otherwise it downloads + decrypts.
     *
     * Must be called off the main thread.
     */
    suspend fun prepareStream(
        context: Context,
        resolved: AmazonAudioProvider.Resolved,
    ): String = withContext(Dispatchers.IO) {
        val asin = resolved.trackId
        val cached = getCachedFlac(asin)
        if (cached != null && cached.exists() && cached.length() > 0) {
            Timber.tag(TAG).d("Cache hit for ASIN $asin -> ${cached.absolutePath}")
            return@withContext cached.absolutePath
        }

        // Coalesce concurrent decrypt requests for the same ASIN.
        val existing = activeJobs[asin]
        if (existing != null && existing.isActive) {
            existing.join()
            val afterJoin = getCachedFlac(asin)
            if (afterJoin != null && afterJoin.exists() && afterJoin.length() > 0) {
                return@withContext afterJoin.absolutePath
            }
        }

        val key = resolved.decryptionKey
            ?: throw AmazonAudioProvider.AmazonResolutionException(
                "No decryption key for ASIN $asin — cannot run FFmpeg",
            )

        try {
            downloadAndDecrypt(context, asin, resolved.mediaUri, key)
        } finally {
            activeJobs.remove(asin)
        }

        val output = getCachedFlac(asin)
            ?: throw AmazonAudioProvider.AmazonResolutionException(
                "FFmpeg produced no output file for ASIN $asin",
            )
        if (!output.exists() || output.length() == 0L) {
            throw AmazonAudioProvider.AmazonResolutionException(
                "FFmpeg output is empty for ASIN $asin",
            )
        }
        output.absolutePath
    }

    /**
     * Returns the cached decrypted FLAC for [asin] if present, or null.
     * Does not verify the file is non-empty — callers should check.
     */
    fun getCachedFlac(asin: String): File? {
        val dir = cacheRoot() ?: return null
        val f = File(dir, "$asin$FLAC_SUFFIX")
        return if (f.exists()) f else null
    }

    /**
     * Removes the cached decrypt for [asin] (if any). Called when the provider
     * cache is invalidated so stale decrypts don't linger.
     */
    fun clearCache(asin: String) {
        getCachedFlac(asin)?.delete()
        cacheRoot()?.let { File(it, "$asin$ENC_SUFFIX").delete() }
    }

    /**
     * Wipes the entire Amazon FLAC cache. Intended for settings / low-disk actions.
     */
    fun clearAllCache() {
        cacheRoot()?.deleteRecursively()
    }

    private fun downloadAndDecrypt(
        context: Context,
        asin: String,
        streamUrl: String,
        decryptionKey: String,
    ) {
        val workDir = ensureCacheDir(context)
        val encFile = File(workDir, "$asin$ENC_SUFFIX")
        val outFile = File(workDir, "$asin$FLAC_SUFFIX")

        // If a previous run left a partial/corrupt output, clear it so FFmpeg
        // doesn't refuse to overwrite.
        outFile.delete()

        // 1. Download the encrypted stream to disk. Streaming directly into
        //    FFmpeg via pipe is possible but fragile on Android (pipe buffer
        //    sizes, deadlock risk). A file is reliable and doubles as cache.
        if (!encFile.exists() || encFile.length() == 0L) {
            Timber.tag(TAG).d("Downloading encrypted stream for $asin from $streamUrl")
            downloadToFile(streamUrl, encFile)
        }
        val encSize = encFile.length()
        Timber.tag(TAG).i("Downloaded $encSize bytes for ASIN $asin")

        // 2. Decrypt + remux to FLAC. `-c copy` avoids re-encoding — FFmpeg
        //    strips the CENC encryption at the container level and copies the
        //    FLAC track out untouched.
        val command = buildString {
            append("-y ")
            append("-decryption_key ").append(decryptionKey).append(' ')
            append("-i \"").append(encFile.absolutePath).append("\" ")
            append("-c copy ")
            append("-vn ")            // no video (Amazon CMAF can carry still frames)
            append("\"").append(outFile.absolutePath).append("\"")
        }
        Timber.tag(TAG).d("Running FFmpeg: $command")

        val session = FFmpegKit.execute(command)
        val returnCode = session.returnCode

        if (returnCode == null || !ReturnCode.isSuccess(returnCode)) {
            val logs = session.allLogsAsString.orEmpty()
            val failStack = session.failStackTrace.orEmpty()
            Timber.tag(TAG).e(
                "FFmpeg failed for ASIN $asin (rc=${returnCode?.value}): $logs\n$failStack",
            )
            // Clean up a possibly-corrupt partial output.
            outFile.delete()
            throw AmazonAudioProvider.AmazonResolutionException(
                "FFmpeg decryption failed for ASIN $asin (rc=${returnCode?.value})",
            )
        }

        Timber.tag(TAG).i(
            "FFmpeg decrypt OK for ASIN $asin: ${outFile.length()} bytes -> ${outFile.absolutePath}",
        )

        // Free the encrypted blob — we keep only the clear FLAC for replay.
        encFile.delete()
    }

    private fun downloadToFile(url: String, dest: File) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", AmazonAudioProvider.BROWSER_USER_AGENT)
            .header("Range", "bytes=0-")
            .build()

        AmazonAudioProvider.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw AmazonAudioProvider.AmazonResolutionException(
                    "Amazon stream download failed: HTTP ${response.code}",
                )
            }
            val body = response.body ?: throw AmazonAudioProvider.AmazonResolutionException(
                "Amazon stream download returned null body",
            )
            dest.parentFile?.mkdirs()
            try {
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                }
            } catch (e: IOException) {
                dest.delete()
                throw AmazonAudioProvider.AmazonResolutionException(
                    "Amazon stream download I/O error: ${e.message}", e,
                )
            }
        }
    }

    private fun ensureCacheDir(context: Context): File =
        File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }

    /**
     * Best-effort cache root lookup. Returns null if the static cacheDir hasn't
     * been initialized yet (before app start) — callers handle null gracefully.
     */
    private fun cacheRoot(): File? = cachedCacheDir

    @Volatile
    private var cachedCacheDir: File? = null

    /**
     * Must be called once at app startup (e.g. from MusicService onCreate or
     * the Application) so [clearCache] / [getCachedFlac] can run without a
     * Context (they're invoked from invalidate paths that don't hold one).
     */
    fun init(context: Context) {
        cachedCacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
    }
}
