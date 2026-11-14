package com.metrolist.desktop.storage

import com.metrolist.desktop.state.playback.localPlaybackResource
import com.metrolist.shared.local.LocalTrack
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playback.ffmpeg.FFmpegFrameGrabber
import com.metrolist.shared.youtube.extraction.models.toContentHints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopLocalMusicScannerTest {
    @Test
    fun recursiveScanDeduplicatesOverlappingFoldersAndPreservesFileUris() =
        runBlocking {
            val directory = Files.createTempDirectory("metrofuse-local-scan-").toFile()
            try {
                val album = directory.resolve("Album").apply { mkdirs() }
                val track = album.resolve("Café 100% #1.WAV").apply { writeBytes(byteArrayOf(1)) }
                album.resolve("cover.jpg").writeBytes(byteArrayOf(1))
                val other = directory.resolve("Track.flac").apply { writeBytes(byteArrayOf(2)) }
                var reads = 0
                val scanner =
                    DesktopLocalMusicScanner { file, _ ->
                        reads++
                        LocalTrack(file.toURI().toASCIIString(), file.nameWithoutExtension)
                    }
                val scan = scanner.scan(listOf(directory, album, directory))
                assertEquals(2, reads)
                assertEquals(
                    setOf(track.canonicalFile.toURI().toASCIIString(), other.canonicalFile.toURI().toASCIIString()),
                    scan.tracks
                        .map {
                            it.uri
                        }.toSet(),
                )
                assertTrue(scan.unreadablePaths.isEmpty())
                assertTrue(scan.tracks.any { "%23" in it.uri && "%25" in it.uri && "%C3%A9" in it.uri })
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun corruptFileDoesNotPreventRemainingTracksFromBeingImported() =
        runBlocking {
            val directory = Files.createTempDirectory("metrofuse-local-invalid-").toFile()
            try {
                directory.resolve("bad.mp3").writeBytes(byteArrayOf(1))
                val good = directory.resolve("good.wav").apply { writeBytes(byteArrayOf(2)) }
                val scanner =
                    DesktopLocalMusicScanner { file, _ ->
                        if (file.name == "bad.mp3") error("Damaged container")
                        LocalTrack(file.toURI().toASCIIString(), file.nameWithoutExtension)
                    }
                val scan = scanner.scan(listOf(directory))
                assertEquals(listOf(good.toURI().toASCIIString()), scan.tracks.map { it.uri })
                assertEquals(1, scan.unreadablePaths.size)
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun taggedLocalWaveFeedsNativeMetadataAndExistingPlaybackResource() =
        runBlocking {
            val directory = Files.createTempDirectory("metrofuse-local-wave-").toFile()
            try {
                val file = directory.resolve("Song space %.wav").apply { writeBytes(taggedWave()) }
                val cover = directory.resolve("cover.jpg").apply { writeBytes(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())) }
                val track = DesktopLocalMusicMetadata(directory.resolve("artwork")).read(file)
                requireNotNull(track)
                assertEquals("Offline song", track.title)
                assertEquals("Local artist", track.artist)
                assertEquals(2, track.durationSeconds)
                assertEquals(cover.toURI().toASCIIString(), track.artworkUri)
                val song =
                    SongItem(
                        track.uri,
                        track.title,
                        null,
                        emptyList(),
                        null,
                        duration = track.durationSeconds.toLong(),
                        isLocal = true,
                    )
                val resource = desktopLocalPlaybackResource(track.uri, song)
                requireNotNull(resource)
                assertEquals(file.canonicalPath, resource.url)
                assertEquals("local", resource.clientName)
                assertFalse(resource.requireBoundedRange)
                assertEquals(file.length(), resource.contentLengthBytes)
                val playerResource = assertNotNull(localPlaybackResource(track.uri, song.toContentHints()))
                assertEquals(file.canonicalPath, playerResource.url)
                val grabber = FFmpegFrameGrabber(playerResource.url)
                try {
                    grabber.start()
                    assertNotNull(grabber.grabSamples())
                } finally {
                    grabber.release()
                }
                assertNull(desktopLocalPlaybackResource(track.uri, song.copy(isLocal = false)))
                file.delete()
                assertNull(desktopLocalPlaybackResource(track.uri, song))
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun cancelledScanStopsBeforeReadingAnotherFile() =
        runBlocking {
            val directory = Files.createTempDirectory("metrofuse-local-cancel-").toFile()
            try {
                repeat(3) { directory.resolve("track$it.wav").writeBytes(byteArrayOf(1)) }
                val job = Job()
                var reads = 0
                val scanner =
                    DesktopLocalMusicScanner { file, _ ->
                        reads++
                        job.cancel()
                        LocalTrack(file.toURI().toASCIIString(), file.nameWithoutExtension)
                    }
                assertFailsWith<CancellationException> { withContext(job) { scanner.scan(listOf(directory)) } }
                assertEquals(1, reads)
            } finally {
                directory.deleteRecursively()
            }
        }

    private fun taggedWave(): ByteArray {
        val sampleBytes = 8_000 * 2 * 2

        fun info(
            id: String,
            value: String,
        ): ByteArray {
            val text = (value + '\u0000').toByteArray()
            return ByteBuffer
                .allocate(8 + text.size + text.size % 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(id.toByteArray())
                .putInt(text.size)
                .put(text)
                .array()
        }
        val metadata = "INFO".toByteArray() + info("INAM", "Offline song") + info("IART", "Local artist")
        val bytes = ByteBuffer.allocate(44 + sampleBytes + 8 + metadata.size).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(bytes.capacity() - 8).put("WAVEfmt ".toByteArray())
        bytes
            .putInt(16)
            .putShort(1)
            .putShort(1)
            .putInt(8_000)
            .putInt(16_000)
            .putShort(2)
            .putShort(16)
        bytes.put("data".toByteArray()).putInt(sampleBytes).put(ByteArray(sampleBytes))
        bytes.put("LIST".toByteArray()).putInt(metadata.size).put(metadata)
        return bytes.array()
    }
}
