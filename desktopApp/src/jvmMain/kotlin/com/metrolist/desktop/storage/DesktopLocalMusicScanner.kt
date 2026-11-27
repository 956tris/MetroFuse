package com.metrolist.desktop.storage

import com.metrolist.shared.local.LocalTrack
import com.metrolist.shared.playback.ffmpeg.FFmpegFrameGrabber
import com.metrolist.shared.utils.XdgUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB.Callback_Pointer
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVDictionaryEntry
import org.bytedeco.ffmpeg.global.avformat.AV_DISPOSITION_ATTACHED_PIC
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AV_DICT_IGNORE_SUFFIX
import org.bytedeco.ffmpeg.global.avutil.AV_TIME_BASE
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_get
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.javacpp.Pointer
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale

internal data class DesktopLocalMusicScan(
    val tracks: List<LocalTrack>,
    val unreadablePaths: List<String>,
    val skippedUnchangedCount: Int = 0,
)

internal class DesktopLocalMusicScanner(
    private val metadata: (File, Job?) -> LocalTrack? = DesktopLocalMusicMetadata()::read,
) {
    suspend fun scan(
        folders: List<File>,
        shouldSkip: (uri: String, lastModifiedMillis: Long) -> Boolean = { _, _ -> false },
    ): DesktopLocalMusicScan =
        withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            val tracks = linkedMapOf<String, LocalTrack>()
            val visited = mutableSetOf<Path>()
            val failures = mutableListOf<String>()
            var skippedUnchanged = 0
            for (folder in folders.distinctBy { it.canonicalPath }) {
                context.ensureActive()
                if (!folder.isDirectory || !folder.canRead()) {
                    failures += folder.absolutePath
                    continue
                }
                val rootPath = folder.toPath()
                Files.walkFileTree(
                    rootPath,
                    object : SimpleFileVisitor<Path>() {
                        override fun preVisitDirectory(
                            directory: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            context.ensureActive()
                            if (directory != rootPath && directory.fileName?.toString() in SKIPPED_DIRECTORIES) {
                                return FileVisitResult.SKIP_SUBTREE
                            }
                            return try {
                                if (visited.add(directory.toRealPath())) FileVisitResult.CONTINUE else FileVisitResult.SKIP_SUBTREE
                            } catch (_: IOException) {
                                failures += directory.toString()
                                FileVisitResult.SKIP_SUBTREE
                            }
                        }

                        override fun visitFile(
                            path: Path,
                            attributes: BasicFileAttributes,
                        ): FileVisitResult {
                            context.ensureActive()
                            if (!attributes.isRegularFile) return FileVisitResult.CONTINUE
                            val file = path.toFile()
                            if (file.extension.lowercase(Locale.ROOT) !in AUDIO_EXTENSIONS) return FileVisitResult.CONTINUE
                            try {
                                val canonical = file.canonicalFile
                                val uri = canonical.toURI().toASCIIString()
                                val lastModified = attributes.lastModifiedTime().toMillis()
                                if (shouldSkip(uri, lastModified)) {
                                    skippedUnchanged++
                                    return FileVisitResult.CONTINUE
                                }
                                metadata(canonical, context[Job])?.let { tracks[it.uri] = it }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                failures += file.absolutePath
                            }
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFileFailed(
                            path: Path,
                            error: IOException,
                        ): FileVisitResult {
                            context.ensureActive()
                            failures += path.toString()
                            return FileVisitResult.CONTINUE
                        }

                        override fun postVisitDirectory(
                            directory: Path,
                            error: IOException?,
                        ): FileVisitResult {
                            context.ensureActive()
                            if (error != null) failures += directory.toString()
                            return FileVisitResult.CONTINUE
                        }
                    },
                )
            }
            DesktopLocalMusicScan(tracks.values.toList(), failures.distinct(), skippedUnchanged)
        }

    companion object {
        private val SKIPPED_DIRECTORIES = setOf(".git", "node_modules")
        private val AUDIO_EXTENSIONS =
            setOf("mp3", "m4a", "mp4", "aac", "flac", "wav", "ogg", "opus", "wma", "aif", "aiff", "mka", "ape", "wv", "alac", "ac3", "dts")
    }
}

/** Reads local containers through the same bundled FFmpeg libraries as the player. */
internal class DesktopLocalMusicMetadata(
    private val artworkDirectory: File = File(XdgUtils.getXdgCacheHome(), "metrolist/local-artwork"),
) {
    fun read(
        file: File,
        job: Job? = null,
    ): LocalTrack? {
        FFmpegFrameGrabber.tryLoad()
        val deadline = System.nanoTime() + 10_000_000_000L
        val interrupt =
            object : Callback_Pointer() {
                override fun call(pointer: Pointer?): Int = if (job?.isActive == false || System.nanoTime() >= deadline) 1 else 0
            }
        val context = avformat_alloc_context() ?: throw IOException("Could not allocate audio metadata context")
        val options = AVDictionary(null)
        var opened = false
        try {
            context.interrupt_callback(AVIOInterruptCB().callback(interrupt))
            context.max_streams(64)
            av_dict_set(options, "protocol_whitelist", "file", 0)
            av_dict_set(options, "probesize", "1048576", 0)
            av_dict_set(options, "analyzeduration", "2000000", 0)
            if (avformat_open_input(context, file.absolutePath, null, options) < 0) {
                job?.ensureActive()
                throw IOException("Could not inspect local audio file")
            }
            opened = true
            if (avformat_find_stream_info(context, null as AVDictionary?) < 0) {
                job?.ensureActive()
                throw IOException("Could not read local audio streams")
            }
            job?.ensureActive()
            val audio =
                (0 until context.nb_streams())
                    .map { context.streams(it) }
                    .firstOrNull { it.codecpar().codec_type() == AVMEDIA_TYPE_AUDIO } ?: return null
            val tags = readTags(context.metadata()) + readTags(audio.metadata())

            fun tag(vararg names: String): String? = names.firstNotNullOfOrNull { tags[it]?.trim()?.takeIf(String::isNotBlank) }
            val cover =
                (0 until context.nb_streams())
                    .asSequence()
                    .map { context.streams(it) }
                    .firstOrNull { it.disposition() and AV_DISPOSITION_ATTACHED_PIC != 0 }
                    ?.attached_pic()
                    ?.let { packet ->
                        if (packet.size() !in 1..10_485_760) return@let null
                        val bytes = ByteArray(packet.size())
                        packet.data().get(bytes)
                        storeArtwork(bytes)
                    } ?: sidecarArtwork(file.parentFile)
            return LocalTrack(
                uri = file.toURI().toASCIIString(),
                title = tag("title") ?: file.nameWithoutExtension.ifBlank { file.name },
                artist = tag("artist", "album_artist", "albumartist"),
                album = tag("album"),
                year = tag("date", "year")?.take(4)?.toIntOrNull(),
                trackNumber = tag("track", "tracknumber")?.substringBefore('/')?.toIntOrNull(),
                durationSeconds = (context.duration() / AV_TIME_BASE).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
                artworkUri = cover?.toURI()?.toASCIIString(),
                dateModifiedMillis = file.lastModified().takeIf { it > 0 },
            )
        } finally {
            av_dict_free(options)
            if (opened) avformat_close_input(context)
            context.close()
            interrupt.close()
        }
    }

    private fun readTags(dictionary: AVDictionary?): Map<String, String> {
        if (dictionary == null || dictionary.isNull) return emptyMap()
        val tags = mutableMapOf<String, String>()
        var entry: AVDictionaryEntry? = null
        repeat(256) {
            entry = av_dict_get(dictionary, "", entry, AV_DICT_IGNORE_SUFFIX)
            if (entry == null || entry!!.isNull) return tags
            tags[entry!!.key().string.lowercase(Locale.ROOT)] = entry!!.value().string
        }
        return tags
    }

    private fun storeArtwork(bytes: ByteArray): File? {
        val extension =
            when {
                bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "jpg"

                bytes.size >= 8 &&
                    bytes
                        .copyOfRange(
                            0,
                            8,
                        ).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
                -> "png"

                else -> return null
            }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        var temporary: Path? = null
        return try {
            if (!artworkDirectory.mkdirs() && !artworkDirectory.isDirectory) return null
            val destination = File(artworkDirectory, "$digest.$extension")
            if (destination.isFile && destination.length() == bytes.size.toLong()) return destination
            temporary = Files.createTempFile(artworkDirectory.toPath(), "cover-", ".tmp")
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            destination
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } finally {
            temporary?.let {
                try {
                    Files.deleteIfExists(it)
                } catch (_: IOException) {
                } catch (_: SecurityException) {
                }
            }
        }
    }

    private fun sidecarArtwork(folder: File): File? =
        folder.listFiles()?.firstOrNull {
            it.isFile && it.extension.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp") &&
                it.nameWithoutExtension.lowercase(Locale.ROOT) in setOf("cover", "folder", "front", "album")
        }
}
