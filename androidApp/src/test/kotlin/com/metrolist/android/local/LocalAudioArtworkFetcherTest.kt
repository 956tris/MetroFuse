package com.metrolist.android.local

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import coil3.ImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [33])
class LocalAudioArtworkFetcherTest {
    @Test
    fun imageRequestsKeepFileCoversSeparateAndReloadEditedArtwork() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val first = Uri.parse("content://media/external/audio/media/17")
            val second = Uri.parse("content://media/external/audio/media/18")
            val pictures = mutableMapOf(first to picture(Color.RED), second to picture(Color.BLUE))
            val reads = mutableListOf<Uri>()
            val loader =
                ImageLoader
                    .Builder(context)
                    .components {
                        add(
                            LocalAudioArtworkFetcher.Factory { _, uri ->
                                reads += uri
                                pictures[uri]
                            },
                        )
                    }.diskCache(null)
                    .build()
            try {
                suspend fun color(
                    uri: Uri,
                    modified: Long,
                ): Int {
                    val result =
                        loader.execute(
                            ImageRequest
                                .Builder(context)
                                .data(localAudioArtworkUri(uri, modified))
                                .size(8, 8)
                                .allowHardware(false)
                                .build(),
                        )
                    assertTrue(result is SuccessResult)
                    return (result as SuccessResult).image.toBitmap().getPixel(0, 0)
                }
                assertEquals(Color.RED, color(first, 1L))
                assertEquals(Color.BLUE, color(second, 1L))
                pictures[first] = picture(Color.GREEN)
                assertEquals(Color.GREEN, color(first, 2L))
                assertEquals(Color.BLUE, color(second, 1L))
                assertTrue(reads.all { it == first || it == second })
            } finally {
                loader.shutdown()
            }
        }

    @Test
    fun missingEmbeddedArtworkDoesNotBorrowAnotherCoverOrReadRemoteAudio() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val reads = mutableListOf<Uri>()
            val loader =
                ImageLoader
                    .Builder(context)
                    .components {
                        add(
                            LocalAudioArtworkFetcher.Factory { _, uri ->
                                reads += uri
                                null
                            },
                        )
                    }.diskCache(null)
                    .build()
            try {
                val audio = Uri.parse("content://media/external/audio/media/19")
                val missing = loader.execute(ImageRequest.Builder(context).data(localAudioArtworkUri(audio, null)).build())
                assertTrue(missing is ErrorResult)
                assertTrue((missing as ErrorResult).throwable is FileNotFoundException)
                val remote = Uri.parse("https://audio.example.test/track.mp3")
                val rejected = loader.execute(ImageRequest.Builder(context).data(localAudioArtworkUri(remote, null)).build())
                assertTrue(rejected is ErrorResult)
                assertEquals(listOf(audio), reads)
            } finally {
                loader.shutdown()
            }
        }

    private fun picture(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        try {
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
