package dev.metrofuse.runtime

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

actual fun decodeArtwork(bytes: ByteArray): ImageBitmap {
    ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
        val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: error("Unsupported image")
        try { reader.input = stream; require(reader.getWidth(0).toLong() * reader.getHeight(0) <= 4_000_000) { "Artwork exceeds 4MP" } }
        finally { reader.dispose() }
    }
    return Image.makeFromEncoded(bytes).toComposeImageBitmap()
}
