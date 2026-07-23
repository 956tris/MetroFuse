package com.metrolist.music.utils.discord

import android.graphics.Bitmap
import java.io.OutputStream
import kotlin.math.roundToInt

internal class DiscordCanvasGifEncoder(
    private val width: Int,
    private val height: Int,
    private val delayCentiseconds: Int,
) {
    private val indexedPixels = ByteArray(width * height)
    private var started = false
    private var output: OutputStream? = null

    fun start(output: OutputStream) {
        this.output = output
        writeAscii("GIF89a")
        writeShort(width)
        writeShort(height)
        writeByte(0xF7)
        writeByte(0)
        writeByte(0)
        writeRgb332Palette()
        writeLoopExtension()
        started = true
    }

    fun addFrame(bitmap: Bitmap) {
        check(started) { "GIF encoder must be started before adding frames" }
        check(bitmap.width == width && bitmap.height == height) {
            "Bitmap must match GIF dimensions"
        }

        bitmap.toRgb332(indexedPixels)
        writeGraphicControlExtension()
        writeImageDescriptor()
        writeImageData(indexedPixels)
    }

    fun finish() {
        if (!started) return
        writeByte(0x3B)
        output?.flush()
        output = null
        started = false
    }

    private fun Bitmap.toRgb332(destination: ByteArray) {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        pixels.forEachIndexed { index, color ->
            val red = (color ushr 16) and 0xFF
            val green = (color ushr 8) and 0xFF
            val blue = color and 0xFF
            destination[index] =
                (((red ushr 5) shl 5) or ((green ushr 5) shl 2) or (blue ushr 6)).toByte()
        }
    }

    private fun writeRgb332Palette() {
        repeat(256) { index ->
            val red = ((index ushr 5) and 0x07).scaleChannel(7)
            val green = ((index ushr 2) and 0x07).scaleChannel(7)
            val blue = (index and 0x03).scaleChannel(3)
            writeByte(red)
            writeByte(green)
            writeByte(blue)
        }
    }

    private fun Int.scaleChannel(max: Int): Int =
        ((this.toFloat() / max.toFloat()) * 255f).roundToInt().coerceIn(0, 255)

    private fun writeLoopExtension() {
        writeByte(0x21)
        writeByte(0xFF)
        writeByte(11)
        writeAscii("NETSCAPE2.0")
        writeByte(3)
        writeByte(1)
        writeShort(0)
        writeByte(0)
    }

    private fun writeGraphicControlExtension() {
        writeByte(0x21)
        writeByte(0xF9)
        writeByte(4)
        writeByte(0x04)
        writeShort(delayCentiseconds)
        writeByte(0)
        writeByte(0)
    }

    private fun writeImageDescriptor() {
        writeByte(0x2C)
        writeShort(0)
        writeShort(0)
        writeShort(width)
        writeShort(height)
        writeByte(0)
    }

    private fun writeImageData(pixels: ByteArray) {
        val minimumCodeSize = 8
        val clearCode = 1 shl minimumCodeSize
        val endCode = clearCode + 1
        val codeSize = minimumCodeSize + 1

        writeByte(minimumCodeSize)
        GifSubBlockBitWriter(output ?: return).apply {
            var codesSinceClear = 0
            writeCode(clearCode, codeSize)
            pixels.forEach {
                if (codesSinceClear >= 250) {
                    writeCode(clearCode, codeSize)
                    codesSinceClear = 0
                }
                writeCode(it.toInt() and 0xFF, codeSize)
                codesSinceClear++
            }
            writeCode(endCode, codeSize)
            finish()
        }
    }

    private fun writeAscii(value: String) {
        output?.write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun writeShort(value: Int) {
        writeByte(value and 0xFF)
        writeByte((value ushr 8) and 0xFF)
    }

    private fun writeByte(value: Int) {
        output?.write(value and 0xFF)
    }

    private class GifSubBlockBitWriter(
        private val output: OutputStream,
    ) {
        private val block = ByteArray(255)
        private var blockSize = 0
        private var accumulator = 0
        private var bitCount = 0

        fun writeCode(code: Int, codeSize: Int) {
            accumulator = accumulator or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                writeDataByte(accumulator and 0xFF)
                accumulator = accumulator ushr 8
                bitCount -= 8
            }
        }

        fun finish() {
            if (bitCount > 0) {
                writeDataByte(accumulator and 0xFF)
            }
            flushBlock()
            output.write(0)
        }

        private fun writeDataByte(value: Int) {
            block[blockSize++] = value.toByte()
            if (blockSize == block.size) {
                flushBlock()
            }
        }

        private fun flushBlock() {
            if (blockSize == 0) return
            output.write(blockSize)
            output.write(block, 0, blockSize)
            blockSize = 0
        }
    }
}
