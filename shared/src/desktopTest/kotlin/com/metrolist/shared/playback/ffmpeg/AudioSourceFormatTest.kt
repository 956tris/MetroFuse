package com.metrolist.shared.playback.ffmpeg

import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioSourceFormatTest {
    @Test(timeout = 30_000)
    fun reportsEncodedPcmDepthBeforeThe16BitOutputConversion() {
        val bytes = pcmWav(sampleRate = 96_000, depth = 24)
        val decoder = AudioDecoder(ByteArrayInputStream(bytes), maximumSize = 0)
        try {
            val format = assertNotNull(decoder.audioFormat)
            assertEquals("pcm_s24le", format.codec)
            assertEquals(24, format.bitDepth)
            assertEquals(96_000, format.sampleRateHz)
            assertEquals(4_608_000L, format.bitrateBps)
            var decodedBytes = 0
            while (true) decodedBytes += (decoder.readPCMFrame() ?: break).size
            assertEquals(1_024 * 2 * 2, decodedBytes)
        } finally {
            decoder.close()
        }
    }

    @Test(timeout = 30_000)
    fun preservesTheExactSampleRateAndBitrate() {
        val decoder = AudioDecoder(ByteArrayInputStream(pcmWav(sampleRate = 44_056, depth = 16)), maximumSize = 0)
        try {
            val format = assertNotNull(decoder.audioFormat)
            assertEquals(44_056, format.sampleRateHz)
            assertEquals(1_409_792L, format.bitrateBps)
        } finally {
            decoder.close()
        }
    }

    @Test(timeout = 30_000)
    fun detectsFlacFromTheActualFileAndReportsItsAverageBitrate() {
        val bytes = javaClass.getResourceAsStream("/ffmpeg/flac-24bit-96000.flac")!!.use { it.readBytes() }
        val file = Files.createTempFile("metrofuse-audio-format-", ".bin")
        try {
            Files.write(file, bytes)
            val decoder = AudioDecoder(file.toString())
            try {
                val format = assertNotNull(decoder.audioFormat)
                assertEquals("flac", format.codec)
                assertEquals(24, format.bitDepth)
                assertEquals(96_000, format.sampleRateHz)
                assertEquals(bytes.size * 64L, format.bitrateBps)
                assertTrue(format.bitrateIsAverage)
            } finally {
                decoder.close()
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test(timeout = 30_000)
    fun doesNotClaimPcmBitDepthForLossyAudio() {
        for (fixture in listOf("opus-251.webm", "aac-140.m4a")) {
            val decoder = AudioDecoder(javaClass.getResourceAsStream("/ffmpeg/$fixture")!!, maximumSize = 0)
            try {
                val format = assertNotNull(decoder.audioFormat)
                assertNull(format.bitDepth, fixture)
                assertTrue(format.sampleRateHz!! > 0, fixture)
            } finally {
                decoder.close()
            }
        }
    }

    @Test(timeout = 30_000)
    fun neverUsesTheVideoContainerBitrateAsUnknownAudioBitrate() {
        val file = Files.createTempFile("metrofuse-audio-format-", ".webm")
        try {
            javaClass
                .getResourceAsStream(
                    "/ffmpeg/audio-quality-with-video.webm",
                )!!
                .use { Files.copy(it, file, StandardCopyOption.REPLACE_EXISTING) }
            val decoder = AudioDecoder(file.toString())
            try {
                val format = assertNotNull(decoder.audioFormat)
                assertEquals("opus", format.codec)
                assertEquals(48_000, format.sampleRateHz)
                assertNull(format.bitrateBps)
            } finally {
                decoder.close()
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test(timeout = 30_000)
    fun closingAPlayerClearsTheSourceFormat() {
        val file = Files.createTempFile("metrofuse-audio-format-", ".wav")
        val player = AudioPlayer()
        try {
            Files.write(file, pcmWav(sampleRate = 48_000, depth = 16))
            assertTrue(player.prepareFile(file.toString(), startPaused = false))
            assertNotNull(player.audioFormat)
            player.close()
            assertNull(player.audioFormat)
        } finally {
            player.close()
            Files.deleteIfExists(file)
        }
    }

    private fun pcmWav(
        sampleRate: Int,
        depth: Int,
    ): ByteArray {
        val dataSize = 1_024 * 2 * depth / 8
        return ByteBuffer
            .allocate(44 + dataSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray())
                putInt(36 + dataSize)
                put("WAVEfmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(2)
                putInt(sampleRate)
                putInt(sampleRate * 2 * depth / 8)
                putShort((2 * depth / 8).toShort())
                putShort(depth.toShort())
                put("data".toByteArray())
                putInt(dataSize)
            }.array()
    }
}
