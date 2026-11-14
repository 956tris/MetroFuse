package dev.metrofuse.youtube

import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.StreamExtractor
import dev.metrofuse.api.AudioQuery
import dev.metrofuse.api.PluginSettings
import dev.metrofuse.api.QualityPreference
import dev.metrofuse.api.QualityPreset
import dev.metrofuse.api.StreamTransport
import dev.metrofuse.api.TrackIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class YouTubeAudioTest {
    private val query = AudioQuery(TrackIdentity("abcdefghijk"), "desktop")

    @Test
    fun streamMappingPreservesTransportExpiryHeadersAndSelectionIntent() =
        runBlocking {
            val settings = Settings()
            val extractor = FixtureExtractor()
            val source = GuestAudio(extractor, settings)
            val stream = assertNotNull(source.resolve(query.copy(quality = QualityPreference(preset = QualityPreset.MP4))).stream)
            assertEquals(AudioQuality.MP4, extractor.quality)
            assertFalse(assertNotNull(extractor.hints).allowSabr)
            assertTrue(assertNotNull(extractor.hints).allowHls)
            assertEquals(StreamTransport.RANGE_HTTP, stream.transport)
            assertEquals("audio/mp4", stream.mimeType)
            assertEquals("mp4a.40.2", stream.codec)
            assertEquals(128000, stream.bitrate)
            assertEquals(44100, stream.sampleRate)
            assertEquals(123456L, stream.contentLength)
            assertEquals(4102444800000L, stream.expiresAtMillis)
            assertEquals(mapOf("User-Agent" to "fixture"), stream.headers)

            extractor.stream = extractor.stream?.copy(audioUrl = "https://fixture.googlevideo.com/audio.m3u8", mimeType = null)
            assertEquals(StreamTransport.HLS, source.resolve(query).stream?.transport)
        }

    @Test
    fun disabledLosslessForeignIdentityAndBitrateLimitsProduceStructuredErrors() =
        runBlocking {
            val settings = Settings()
            val extractor = FixtureExtractor()
            val source = GuestAudio(extractor, settings)
            assertEquals(
                "IDENTITY_REQUIRED",
                source
                    .resolve(
                        query.copy(
                            identity =
                                TrackIdentity(
                                    "abcdefghijk",
                                    mapOf("pluginOwner" to "other.provider"),
                                ),
                        ),
                    ).error
                    ?.code,
            )
            settings.set("audioEnabled", JsonPrimitive(false))
            assertEquals("AUDIO_DISABLED", source.resolve(query).error?.code)
            settings.set("audioEnabled", JsonPrimitive(true))
            assertEquals("LOSSLESS_UNAVAILABLE", source.resolve(query.copy(quality = QualityPreference(lossless = true))).error?.code)
            assertEquals(0, extractor.calls)
            assertEquals("BITRATE_UNAVAILABLE", source.resolve(query.copy(quality = QualityPreference(maxBitrate = 96000))).error?.code)
        }

    @Test
    fun missingStreamsFailAndCancellationPropagates() =
        runBlocking<Unit> {
            val extractor = FixtureExtractor()
            val source = GuestAudio(extractor, Settings())
            extractor.stream = null
            assertEquals("STREAM_UNAVAILABLE", source.resolve(query).error?.code)
            extractor.cancel = true
            assertFailsWith<CancellationException> { source.resolve(query) }
        }

    private class Settings : PluginSettings {
        override val values = MutableStateFlow<Map<String, JsonElement>>(mapOf("audioEnabled" to JsonPrimitive(true)))

        override suspend fun set(
            key: String,
            value: JsonElement,
        ) {
            values.value = values.value + (key to value)
        }
    }

    private class FixtureExtractor : StreamExtractor {
        var calls = 0
        var cancel = false
        var quality: AudioQuality? = null
        var hints: ContentHints? = null
        var stream: ExtractedStream? =
            ExtractedStream(
                videoId = "abcdefghijk",
                audioUrl = "https://fixture.googlevideo.com/audio.mp4",
                headers = mapOf("User-Agent" to "fixture"),
                loudnessDb = null,
                expiresAt = Instant.fromEpochMilliseconds(4102444800000),
                contentLengthBytes = 123456,
                itag = 140,
                mimeType = "audio/mp4",
                codecs = "mp4a.40.2",
                bitrate = 128000,
                sampleRate = 44100,
                clientName = "fixture",
                profileId = "fixture",
                requireBoundedRange = true,
                rangeChunkSizeBytes = 65536,
            )

        override suspend fun prewarm() = Unit

        override suspend fun extract(
            videoId: String,
            hints: ContentHints,
            excludedClients: Set<String>,
            audioQuality: AudioQuality,
            clientPlaybackNonce: String,
        ): ExtractedStream? {
            calls++
            this.hints = hints
            quality = audioQuality
            if (cancel) throw CancellationException("Fixture cancellation")
            return stream
        }
    }
}
