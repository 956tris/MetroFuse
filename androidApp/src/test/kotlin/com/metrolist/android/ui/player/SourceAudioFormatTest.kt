package com.metrolist.android.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SourceAudioFormatTest {
    @Test
    fun compressedSourcesNeverAssumePcmBitDepth() {
        for (mime in listOf(MimeTypes.AUDIO_AAC, MimeTypes.AUDIO_OPUS, MimeTypes.AUDIO_FLAC)) {
            assertNull(sourceAudioBitDepth(Format.Builder().setSampleMimeType(mime).build()))
        }
        assertNull(sourceAudioCodecLabel(Format.Builder().build()))
    }

    @Test
    fun pcmReportsItsActualSourceDepthInEitherByteOrder() {
        for (encoding in listOf(C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN)) {
            val format =
                Format
                    .Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .setPcmEncoding(encoding)
                    .build()
            assertEquals("PCM", sourceAudioCodecLabel(format))
            assertEquals(24, sourceAudioBitDepth(format))
        }
    }
}
