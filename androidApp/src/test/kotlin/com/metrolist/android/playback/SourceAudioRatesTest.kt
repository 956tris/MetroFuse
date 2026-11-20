package com.metrolist.android.playback

import androidx.media3.common.Format
import com.metrolist.shared.youtube.playback.PlaybackResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SourceAudioRatesTest {
    private val resource = PlaybackResource("track", "https://example.test/audio", emptyMap(), null, null)

    @Test
    fun fillsMissingDecoderRatesFromPlaybackResource() {
        val format = Format.Builder().setPeakBitrate(192_000).build()

        val source = format.withSourceAudioRates(resource.copy(bitrate = 128_000, sampleRate = 44_100), 1_200_000, 12_000)

        assertEquals(128_000, source.averageBitrate)
        assertEquals(44_100, source.sampleRate)
        assertEquals(192_000, source.peakBitrate)
    }

    @Test
    fun keepsRatesReportedByDecoder() {
        val format =
            Format
                .Builder()
                .setAverageBitrate(256_000)
                .setSampleRate(48_000)
                .build()

        val source = format.withSourceAudioRates(resource.copy(bitrate = 128_000, sampleRate = 44_100), null, 0)

        assertSame(format, source)
    }

    @Test
    fun estimatesAverageBitrateForAudioFile() {
        val format = Format.Builder().setSampleRate(44_100).build()

        val source = format.withSourceAudioRates(resource, 1_200_000, 12_000)

        assertEquals(800_000, source.averageBitrate)
        assertEquals(44_100, source.sampleRate)
    }

    @Test
    fun leavesRatesUnknownWithoutValidSourceMetadata() {
        val format = Format.Builder().setPeakBitrate(192_000).build()

        val source = format.withSourceAudioRates(resource.copy(bitrate = 0, sampleRate = -1), 1_200_000, 0)

        assertSame(format, source)
    }
}
