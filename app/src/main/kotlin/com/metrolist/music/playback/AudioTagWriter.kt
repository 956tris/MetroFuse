/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.SequenceInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.zip.ZipInputStream

internal data class EmbeddedArtwork(
    val mimeType: String,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val colorDepth: Int,
    val localThumbnailUrl: String?,
)

internal data class EmbeddedCanvas(
    val mimeType: String,
    val bytes: ByteArray,
    val provider: String,
)

internal data class EmbeddedAudioMetadata(
    val title: String,
    val artists: List<String>,
    val album: String?,
    val year: Int?,
    val lyrics: String?,
    val lyricsProvider: String? = null,
    val artwork: EmbeddedArtwork?,
    val canvas: EmbeddedCanvas? = null,
)

internal object AudioTagWriter {
    fun embed(
        file: File,
        extension: String,
        metadata: EmbeddedAudioMetadata,
    ): Result<Boolean> =
        runCatching {
            when (extension.lowercase(Locale.US)) {
                "mp3" -> writeId3v23(file, metadata)
                "flac" -> writeFlac(file, metadata)
                "m4a", "mp4" -> writeMp4(file, metadata)
                else -> false
            }
        }

    fun extractEmbeddedCanvasToCache(
        context: Context,
        trackUri: String,
    ): String? =
        runCatching {
            val uri = trackUri.toUri()
            val canvas =
                openTrackInput(context, uri)?.use { input ->
                    extractEmbeddedCanvas(input)
                } ?: return@runCatching null

            if (canvas.mimeType == METROFUSE_HLS_CANVAS_MIME) {
                return@runCatching extractHlsCanvasPackage(context, trackUri, canvas.bytes)
            }

            val file = cachedCanvasFile(context, trackUri, canvas.mimeType)
            if (!file.exists() || file.length() != canvas.bytes.size.toLong()) {
                file.parentFile?.mkdirs()
                file.outputStream().use { it.write(canvas.bytes) }
            }
            file.toUri().toString()
        }.getOrNull()

    private fun writeId3v23(
        file: File,
        metadata: EmbeddedAudioMetadata,
    ): Boolean {
        val tag = id3v23Tag(metadata)
        val temp = file.resolveSibling("${file.name}.tagged")

        file.inputStream().use { input ->
            temp.outputStream().use { output ->
                output.write(tag)

                val header = ByteArray(ID3_HEADER_SIZE)
                val read = input.read(header)
                val oldTagSize =
                    if (read == ID3_HEADER_SIZE && header[0] == 'I'.code.toByte() &&
                        header[1] == 'D'.code.toByte() &&
                        header[2] == '3'.code.toByte()
                    ) {
                        id3TagSize(header)
                    } else {
                        0
                    }

                if (oldTagSize > 0) {
                    input.skipFully((oldTagSize - read).coerceAtLeast(0))
                } else if (read > 0) {
                    output.write(header, 0, read)
                }
                input.copyTo(output)
            }
        }

        replaceFile(temp, file)
        return true
    }

    private fun id3v23Tag(metadata: EmbeddedAudioMetadata): ByteArray {
        val frames = ByteArrayOutputStream()
        frames.writeTextFrame("TIT2", metadata.title)
        frames.writeTextFrame("TPE1", metadata.artists.joinToString("; "))
        frames.writeTextFrame("TALB", metadata.album)
        frames.writeTextFrame("TYER", metadata.year?.takeIf { it > 0 }?.toString())
        frames.writeUnsyncedLyrics(metadata.lyrics)
        frames.writeUserTextFrame("LYRICS", metadata.lyrics)
        frames.writeUserTextFrame("SYNCEDLYRICS", metadata.lyrics)
        frames.writeAttachedPicture(metadata.artwork)
        frames.writeMetroFuseCanvas(metadata.canvas)

        val body = frames.toByteArray() + ByteArray(ID3_PADDING_BYTES)
        return ByteArrayOutputStream().apply {
            write("ID3".toByteArray(StandardCharsets.US_ASCII))
            write(byteArrayOf(3, 0, 0))
            write(syncSafe(body.size))
            write(body)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeTextFrame(
        id: String,
        value: String?,
    ) {
        val text = value?.takeIf { it.isNotBlank() } ?: return
        writeFrame(id, byteArrayOf(TEXT_ENCODING_UTF16) + utf16WithBom(text))
    }

    private fun ByteArrayOutputStream.writeUnsyncedLyrics(lyrics: String?) {
        val text = lyrics?.takeIf { it.isNotBlank() } ?: return
        val payload = ByteArrayOutputStream().apply {
            write(TEXT_ENCODING_UTF16.toInt())
            write("eng".toByteArray(StandardCharsets.US_ASCII))
            write(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0))
            write(utf16WithBom(text))
        }.toByteArray()
        writeFrame("USLT", payload)
    }

    private fun ByteArrayOutputStream.writeUserTextFrame(
        description: String,
        value: String?,
    ) {
        val text = value?.takeIf { it.isNotBlank() } ?: return
        val payload = byteArrayOf(TEXT_ENCODING_UTF16) +
            utf16WithBom(description) +
            byteArrayOf(0, 0) +
            utf16WithBom(text)
        writeFrame("TXXX", payload)
    }

    private fun ByteArrayOutputStream.writeAttachedPicture(artwork: EmbeddedArtwork?) {
        artwork ?: return
        val payload = ByteArrayOutputStream().apply {
            write(0)
            write(artwork.mimeType.toByteArray(StandardCharsets.ISO_8859_1))
            write(0)
            write(3)
            write(0)
            write(artwork.bytes)
        }.toByteArray()
        writeFrame("APIC", payload)
    }

    private fun ByteArrayOutputStream.writeMetroFuseCanvas(canvas: EmbeddedCanvas?) {
        canvas ?: return
        val payload = ByteArrayOutputStream().apply {
            write(0)
            write(canvas.mimeType.toByteArray(StandardCharsets.ISO_8859_1))
            write(0)
            write(canvasFileName(canvas.mimeType).toByteArray(StandardCharsets.ISO_8859_1))
            write(0)
            write(METROFUSE_CANVAS_DESCRIPTION.toByteArray(StandardCharsets.ISO_8859_1))
            write(0)
            write(canvas.bytes)
        }.toByteArray()
        writeFrame("GEOB", payload)
        writeUserTextFrame(METROFUSE_CANVAS_PROVIDER, canvas.provider)
    }

    private fun ByteArrayOutputStream.writeFrame(
        id: String,
        payload: ByteArray,
    ) {
        if (payload.isEmpty()) return
        write(id.toByteArray(StandardCharsets.US_ASCII))
        writeInt32(payload.size)
        write(byteArrayOf(0, 0))
        write(payload)
    }

    private fun writeFlac(
        file: File,
        metadata: EmbeddedAudioMetadata,
    ): Boolean {
        val temp = file.resolveSibling("${file.name}.tagged")

        file.inputStream().use { input ->
            val marker = input.readExactly(4)
            if (!marker.contentEquals("fLaC".toByteArray(StandardCharsets.US_ASCII))) {
                return false
            }

            val blocks = mutableListOf<FlacBlock>()
            while (true) {
                val header = input.readExactly(4)
                val isLast = header[0].toInt() and 0x80 != 0
                val type = header[0].toInt() and 0x7F
                val length = ((header[1].toInt() and 0xFF) shl 16) or
                    ((header[2].toInt() and 0xFF) shl 8) or
                    (header[3].toInt() and 0xFF)
                val data = input.readExactly(length)
                if (type != FLAC_VORBIS_COMMENT && type != FLAC_PICTURE) {
                    blocks += FlacBlock(type, data)
                }
                if (isLast) break
            }

            val insertAt = blocks.indexOfFirst { it.type == FLAC_STREAMINFO }
                .takeIf { it >= 0 }
                ?.plus(1)
                ?: 0
            blocks.add(insertAt, FlacBlock(FLAC_VORBIS_COMMENT, vorbisComment(metadata)))
            metadata.artwork?.let { artwork ->
                blocks.add(insertAt + 1, FlacBlock(FLAC_PICTURE, flacPicture(artwork)))
            }

            temp.outputStream().use { output ->
                output.write(marker)
                blocks.forEachIndexed { index, block ->
                    output.writeFlacBlock(
                        type = block.type,
                        data = block.data,
                        isLast = index == blocks.lastIndex,
                    )
                }
                input.copyTo(output)
            }
        }

        replaceFile(temp, file)
        return true
    }

    private fun vorbisComment(metadata: EmbeddedAudioMetadata): ByteArray {
        val comments = buildList {
            addVorbisComment("TITLE", metadata.title)
            addVorbisComment("ARTIST", metadata.artists.joinToString("; "))
            addVorbisComment("ALBUM", metadata.album)
            addVorbisComment("DATE", metadata.year?.takeIf { it > 0 }?.toString())
            addVorbisComment("LYRICS", metadata.lyrics)
            addVorbisComment("UNSYNCEDLYRICS", metadata.lyrics)
            addVorbisComment("UNSYNCED LYRICS", metadata.lyrics)
            addVorbisComment("SYNCEDLYRICS", metadata.lyrics)
            addVorbisComment("SYNCLYRICS", metadata.lyrics)
            addVorbisComment(
                "METADATA_BLOCK_PICTURE",
                metadata.artwork?.let { Base64.getEncoder().encodeToString(flacPicture(it)) },
            )
            metadata.canvas?.let { canvas ->
                addVorbisComment(METROFUSE_CANVAS_MIME, canvas.mimeType)
                addVorbisComment(METROFUSE_CANVAS_PROVIDER, canvas.provider)
                addVorbisComment(METROFUSE_CANVAS_BASE64, Base64.getEncoder().encodeToString(canvas.bytes))
            }
        }
        val vendor = "MetroFuse".toByteArray(StandardCharsets.UTF_8)
        return ByteArrayOutputStream().apply {
            writeInt32LittleEndian(vendor.size)
            write(vendor)
            writeInt32LittleEndian(comments.size)
            comments.forEach { comment ->
                val bytes = comment.toByteArray(StandardCharsets.UTF_8)
                writeInt32LittleEndian(bytes.size)
                write(bytes)
            }
        }.toByteArray()
    }

    private fun MutableList<String>.addVorbisComment(
        key: String,
        value: String?,
    ) {
        val text = value?.takeIf { it.isNotBlank() } ?: return
        add("$key=$text")
    }

    private fun flacPicture(artwork: EmbeddedArtwork): ByteArray =
        ByteArrayOutputStream().apply {
            writeInt32(3)
            val mime = artwork.mimeType.toByteArray(StandardCharsets.US_ASCII)
            writeInt32(mime.size)
            write(mime)
            writeInt32(0)
            writeInt32(artwork.width)
            writeInt32(artwork.height)
            writeInt32(artwork.colorDepth)
            writeInt32(0)
            writeInt32(artwork.bytes.size)
            write(artwork.bytes)
        }.toByteArray()

    private fun writeMp4(
        file: File,
        metadata: EmbeddedAudioMetadata,
    ): Boolean {
        val source = file.readBytes()
        val topLevel = parseMp4Boxes(source, 0, source.size)
        val moov = topLevel.firstOrNull { it.type.contentEquals(MP4_MOOV) } ?: return false
        val mdat = topLevel.firstOrNull { it.type.contentEquals(MP4_MDAT) }
        val oldMoov = source.copyOfRange(moov.start, moov.end)
        var newMoov = rebuildMoov(oldMoov, metadata)
        val delta = newMoov.size - oldMoov.size

        if (delta != 0 && mdat != null && moov.start < mdat.start) {
            newMoov = adjustMp4ChunkOffsets(newMoov, delta)
        }

        val temp = file.resolveSibling("${file.name}.tagged")
        temp.outputStream().use { output ->
            output.write(source, 0, moov.start)
            output.write(newMoov)
            output.write(source, moov.end, source.size - moov.end)
        }
        replaceFile(temp, file)
        return true
    }

    private fun rebuildMoov(
        moov: ByteArray,
        metadata: EmbeddedAudioMetadata,
    ): ByteArray {
        val children = parseMp4Boxes(moov, MP4_BOX_HEADER_SIZE, moov.size)
        val rebuilt = ByteArrayOutputStream()
        var handledUdta = false

        children.forEach { child ->
            if (child.type.contentEquals(MP4_UDTA)) {
                rebuilt.write(rebuildUdta(moov.copyOfRange(child.start, child.end), metadata))
                handledUdta = true
            } else {
                rebuilt.write(moov, child.start, child.size)
            }
        }

        if (!handledUdta) {
            rebuilt.write(buildMp4Box(MP4_UDTA, buildMeta(metadata)))
        }

        return buildMp4Box(MP4_MOOV, rebuilt.toByteArray())
    }

    private fun rebuildUdta(
        udta: ByteArray,
        metadata: EmbeddedAudioMetadata,
    ): ByteArray {
        val children = parseMp4Boxes(udta, MP4_BOX_HEADER_SIZE, udta.size)
        val rebuilt = ByteArrayOutputStream()
        var handledMeta = false

        children.forEach { child ->
            if (child.type.contentEquals(MP4_META)) {
                rebuilt.write(rebuildMeta(udta.copyOfRange(child.start, child.end), metadata))
                handledMeta = true
            } else {
                rebuilt.write(udta, child.start, child.size)
            }
        }

        if (!handledMeta) {
            rebuilt.write(buildMeta(metadata))
        }

        return buildMp4Box(MP4_UDTA, rebuilt.toByteArray())
    }

    private fun buildMeta(metadata: EmbeddedAudioMetadata): ByteArray =
        buildMp4Box(
            MP4_META,
            ByteArrayOutputStream().apply {
                writeInt32(0)
                write(buildMp4Handler())
                write(buildIlst(metadata, emptyList()))
            }.toByteArray(),
        )

    private fun rebuildMeta(
        meta: ByteArray,
        metadata: EmbeddedAudioMetadata,
    ): ByteArray {
        if (meta.size < MP4_BOX_HEADER_SIZE + 4) return buildMeta(metadata)

        val fullBoxHeader = meta.copyOfRange(MP4_BOX_HEADER_SIZE, MP4_BOX_HEADER_SIZE + 4)
        val children = parseMp4Boxes(meta, MP4_BOX_HEADER_SIZE + 4, meta.size)
        val rebuilt = ByteArrayOutputStream()
        var handledIlst = false
        var hasHandler = false

        children.forEach { child ->
            when {
                child.type.contentEquals(MP4_HDLR) -> {
                    hasHandler = true
                    rebuilt.write(meta, child.start, child.size)
                }
                child.type.contentEquals(MP4_ILST) -> {
                    rebuilt.write(rebuildIlst(meta.copyOfRange(child.start, child.end), metadata))
                    handledIlst = true
                }
                else -> rebuilt.write(meta, child.start, child.size)
            }
        }

        val payload = ByteArrayOutputStream().apply {
            write(fullBoxHeader)
            if (!hasHandler) {
                write(buildMp4Handler())
            }
            write(rebuilt.toByteArray())
            if (!handledIlst) {
                write(buildIlst(metadata, emptyList()))
            }
        }.toByteArray()

        return buildMp4Box(MP4_META, payload)
    }

    private fun rebuildIlst(
        ilst: ByteArray,
        metadata: EmbeddedAudioMetadata,
    ): ByteArray {
        val keptItems = parseMp4Boxes(ilst, MP4_BOX_HEADER_SIZE, ilst.size)
            .filterNot { box -> MP4_REPLACED_ITEM_TYPES.any { it.contentEquals(box.type) } }
            .map { box -> ilst.copyOfRange(box.start, box.end) }
        return buildIlst(metadata, keptItems)
    }

    private fun buildIlst(
        metadata: EmbeddedAudioMetadata,
        keptItems: List<ByteArray>,
    ): ByteArray {
        val items = ByteArrayOutputStream()
        keptItems.forEach(items::write)
        items.writeMp4TextItem(MP4_TITLE, metadata.title)
        items.writeMp4TextItem(MP4_ARTIST, metadata.artists.joinToString("; "))
        items.writeMp4TextItem(MP4_ALBUM, metadata.album)
        items.writeMp4TextItem(MP4_YEAR, metadata.year?.takeIf { it > 0 }?.toString())
        items.writeMp4TextItem(MP4_LYRICS, metadata.lyrics)
        items.writeMp4CoverItem(metadata.artwork)
        items.writeMetroFuseCanvasItem(metadata.canvas)
        return buildMp4Box(MP4_ILST, items.toByteArray())
    }

    private fun buildMp4Handler(): ByteArray =
        buildMp4Box(
            MP4_HDLR,
            ByteArrayOutputStream().apply {
                writeInt32(0)
                writeInt32(0)
                write(MP4_MDIR)
                writeInt32(0)
                writeInt32(0)
                writeInt32(0)
                write("appl".toByteArray(StandardCharsets.US_ASCII))
                write(0)
            }.toByteArray(),
        )

    private fun ByteArrayOutputStream.writeMp4TextItem(
        itemType: ByteArray,
        value: String?,
    ) {
        val text = value?.takeIf { it.isNotBlank() } ?: return
        writeMp4Item(itemType, MP4_DATA_UTF8, text.toByteArray(StandardCharsets.UTF_8))
    }

    private fun ByteArrayOutputStream.writeMp4CoverItem(artwork: EmbeddedArtwork?) {
        artwork ?: return
        val dataType = when (artwork.mimeType.lowercase(Locale.US)) {
            "image/png" -> MP4_DATA_PNG
            else -> MP4_DATA_JPEG
        }
        writeMp4Item(MP4_COVER, dataType, artwork.bytes)
    }

    private fun ByteArrayOutputStream.writeMetroFuseCanvasItem(canvas: EmbeddedCanvas?) {
        canvas ?: return
        writeMp4FreeformItem(METROFUSE_MP4_CANVAS_NAME, MP4_DATA_BINARY, canvas.bytes)
        writeMp4FreeformItem(METROFUSE_MP4_CANVAS_MIME_NAME, MP4_DATA_UTF8, canvas.mimeType.toByteArray(StandardCharsets.UTF_8))
        writeMp4FreeformItem(METROFUSE_MP4_CANVAS_PROVIDER_NAME, MP4_DATA_UTF8, canvas.provider.toByteArray(StandardCharsets.UTF_8))
    }

    private fun ByteArrayOutputStream.writeMp4FreeformItem(
        name: String,
        dataType: Int,
        value: ByteArray,
    ) {
        if (value.isEmpty()) return
        val payload = ByteArrayOutputStream().apply {
            write(buildMp4Box(MP4_MEAN, mp4FreeformText(METROFUSE_MP4_MEAN)))
            write(buildMp4Box(MP4_NAME, mp4FreeformText(name)))
            val dataPayload = ByteArrayOutputStream().apply {
                writeInt32(dataType)
                writeInt32(0)
                write(value)
            }.toByteArray()
            write(buildMp4Box(MP4_DATA, dataPayload))
        }.toByteArray()
        write(buildMp4Box(MP4_FREEFORM, payload))
    }

    private fun mp4FreeformText(value: String): ByteArray =
        ByteArrayOutputStream().apply {
            writeInt32(0)
            write(value.toByteArray(StandardCharsets.UTF_8))
        }.toByteArray()

    private fun ByteArrayOutputStream.writeMp4Item(
        itemType: ByteArray,
        dataType: Int,
        value: ByteArray,
    ) {
        val dataPayload = ByteArrayOutputStream().apply {
            writeInt32(dataType)
            writeInt32(0)
            write(value)
        }.toByteArray()
        write(buildMp4Box(itemType, buildMp4Box(MP4_DATA, dataPayload)))
    }

    private fun buildMp4Box(
        type: ByteArray,
        payload: ByteArray,
    ): ByteArray {
        require(type.size == 4) { "MP4 box type must be four bytes" }
        val size = MP4_BOX_HEADER_SIZE + payload.size
        require(size > 0) { "MP4 box is too large" }
        return ByteArrayOutputStream().apply {
            writeInt32(size)
            write(type)
            write(payload)
        }.toByteArray()
    }

    private fun parseMp4Boxes(
        data: ByteArray,
        start: Int,
        end: Int,
    ): List<Mp4Box> {
        val boxes = mutableListOf<Mp4Box>()
        var offset = start
        while (offset + MP4_BOX_HEADER_SIZE <= end) {
            val declaredSize = data.readUInt32(offset)
            val type = data.copyOfRange(offset + 4, offset + 8)
            val headerSize: Int
            val size: Long
            when (declaredSize) {
                0L -> {
                    headerSize = MP4_BOX_HEADER_SIZE
                    size = (end - offset).toLong()
                }
                1L -> {
                    if (offset + MP4_EXTENDED_BOX_HEADER_SIZE > end) break
                    headerSize = MP4_EXTENDED_BOX_HEADER_SIZE
                    size = data.readUInt64(offset + 8)
                }
                else -> {
                    headerSize = MP4_BOX_HEADER_SIZE
                    size = declaredSize
                }
            }

            if (size < headerSize || offset + size > end) break
            boxes += Mp4Box(
                type = type,
                start = offset,
                headerSize = headerSize,
                size = size.toInt(),
            )
            offset += size.toInt()
        }
        return boxes
    }

    private fun adjustMp4ChunkOffsets(
        moov: ByteArray,
        delta: Int,
    ): ByteArray =
        moov.copyOf().also { data ->
            adjustMp4ChunkOffsets(data, MP4_BOX_HEADER_SIZE, data.size, delta)
        }

    private fun adjustMp4ChunkOffsets(
        data: ByteArray,
        start: Int,
        end: Int,
        delta: Int,
    ) {
        parseMp4Boxes(data, start, end).forEach { box ->
            when {
                box.type.contentEquals(MP4_STCO) -> data.adjustStco(box, delta)
                box.type.contentEquals(MP4_CO64) -> data.adjustCo64(box, delta)
                box.type.contentEquals(MP4_META) -> {
                    adjustMp4ChunkOffsets(data, box.payloadStart + 4, box.end, delta)
                }
                box.type.isMp4ContainerType() -> {
                    adjustMp4ChunkOffsets(data, box.payloadStart, box.end, delta)
                }
            }
        }
    }

    private fun ByteArray.adjustStco(
        box: Mp4Box,
        delta: Int,
    ) {
        val entryCountOffset = box.payloadStart + 4
        if (entryCountOffset + 4 > box.end) return
        val entryCount = readUInt32(entryCountOffset).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        var offset = entryCountOffset + 4
        repeat(entryCount) {
            if (offset + 4 > box.end) return
            val adjusted = readUInt32(offset) + delta
            writeUInt32(offset, adjusted)
            offset += 4
        }
    }

    private fun ByteArray.adjustCo64(
        box: Mp4Box,
        delta: Int,
    ) {
        val entryCountOffset = box.payloadStart + 4
        if (entryCountOffset + 4 > box.end) return
        val entryCount = readUInt32(entryCountOffset).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        var offset = entryCountOffset + 4
        repeat(entryCount) {
            if (offset + 8 > box.end) return
            val adjusted = readUInt64(offset) + delta
            writeUInt64(offset, adjusted)
            offset += 8
        }
    }

    private fun OutputStream.writeFlacBlock(
        type: Int,
        data: ByteArray,
        isLast: Boolean,
    ) {
        require(data.size <= 0xFFFFFF) { "FLAC metadata block is too large" }
        write((if (isLast) 0x80 else 0) or (type and 0x7F))
        write((data.size shr 16) and 0xFF)
        write((data.size shr 8) and 0xFF)
        write(data.size and 0xFF)
        write(data)
    }

    private fun InputStream.readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(bytes, offset, size - offset)
            if (read == -1) error("Unexpected end of file")
            offset += read
        }
        return bytes
    }

    private fun InputStream.readExactlyOrNull(size: Int): ByteArray? =
        runCatching { readExactly(size) }.getOrNull()

    private fun InputStream.readUpTo(size: Int): ByteArray {
        if (size <= 0) return ByteArray(0)
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(bytes, offset, size - offset)
            if (read == -1) break
            offset += read
        }
        return bytes.copyOf(offset)
    }

    private fun InputStream.skipFully(count: Int) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining.toLong()).toInt()
            if (skipped <= 0 && read() == -1) return
            remaining -= skipped.takeIf { it > 0 } ?: 1
        }
    }

    private fun ByteArrayOutputStream.writeInt32(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeInt32LittleEndian(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun utf16WithBom(value: String): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + value.toByteArray(Charsets.UTF_16LE)

    private fun id3TagSize(header: ByteArray): Int {
        val bodySize = syncSafeToInt(header.copyOfRange(6, 10))
        val hasFooter = header[5].toInt() and 0x10 != 0
        return ID3_HEADER_SIZE + bodySize + if (hasFooter) ID3_HEADER_SIZE else 0
    }

    private fun syncSafe(value: Int): ByteArray =
        byteArrayOf(
            ((value shr 21) and 0x7F).toByte(),
            ((value shr 14) and 0x7F).toByte(),
            ((value shr 7) and 0x7F).toByte(),
            (value and 0x7F).toByte(),
        )

    private fun syncSafeToInt(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0x7F) shl 21) or
            ((bytes[1].toInt() and 0x7F) shl 14) or
            ((bytes[2].toInt() and 0x7F) shl 7) or
            (bytes[3].toInt() and 0x7F)

    private fun ByteArray.readUInt32(offset: Int): Long =
        ((this[offset].toLong() and 0xFF) shl 24) or
            ((this[offset + 1].toLong() and 0xFF) shl 16) or
            ((this[offset + 2].toLong() and 0xFF) shl 8) or
            (this[offset + 3].toLong() and 0xFF)

    private fun ByteArray.readUInt64(offset: Int): Long =
        ((this[offset].toLong() and 0xFF) shl 56) or
            ((this[offset + 1].toLong() and 0xFF) shl 48) or
            ((this[offset + 2].toLong() and 0xFF) shl 40) or
            ((this[offset + 3].toLong() and 0xFF) shl 32) or
            ((this[offset + 4].toLong() and 0xFF) shl 24) or
            ((this[offset + 5].toLong() and 0xFF) shl 16) or
            ((this[offset + 6].toLong() and 0xFF) shl 8) or
            (this[offset + 7].toLong() and 0xFF)

    private fun ByteArray.writeUInt32(
        offset: Int,
        value: Long,
    ) {
        require(value in 0..0xFFFF_FFFFL) { "MP4 chunk offset exceeds stco range" }
        this[offset] = ((value ushr 24) and 0xFF).toByte()
        this[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        this[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        this[offset + 3] = (value and 0xFF).toByte()
    }

    private fun ByteArray.writeUInt64(
        offset: Int,
        value: Long,
    ) {
        require(value >= 0) { "MP4 chunk offset cannot be negative" }
        this[offset] = ((value ushr 56) and 0xFF).toByte()
        this[offset + 1] = ((value ushr 48) and 0xFF).toByte()
        this[offset + 2] = ((value ushr 40) and 0xFF).toByte()
        this[offset + 3] = ((value ushr 32) and 0xFF).toByte()
        this[offset + 4] = ((value ushr 24) and 0xFF).toByte()
        this[offset + 5] = ((value ushr 16) and 0xFF).toByte()
        this[offset + 6] = ((value ushr 8) and 0xFF).toByte()
        this[offset + 7] = (value and 0xFF).toByte()
    }

    private fun openTrackInput(
        context: Context,
        uri: Uri,
    ): InputStream? =
        when (uri.scheme) {
            "content" -> context.contentResolver.openInputStream(uri)
            "file" -> uri.path?.let(::File)?.takeIf { it.exists() }?.inputStream()
            null -> File(uri.toString()).takeIf { it.exists() }?.inputStream()
            else -> null
        }

    private fun extractEmbeddedCanvas(input: InputStream): EmbeddedCanvas? {
        val header = input.readUpTo(12)
        if (header.size < 12) return null
        return when {
            header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte() -> {
                val tagSize = id3TagSize(header.copyOfRange(0, ID3_HEADER_SIZE))
                val rest = input.readUpTo((tagSize - header.size).coerceAtLeast(0))
                extractId3Canvas(header + rest)
            }
            header.copyOfRange(0, 4).contentEquals("fLaC".toByteArray(StandardCharsets.US_ASCII)) -> {
                extractFlacCanvas(SequenceInputStream(ByteArrayInputStream(header), input))
            }
            header.copyOfRange(4, 8).contentEquals(MP4_FTYP) -> {
                extractMp4Canvas(header + input.readBytes())
            }
            else -> null
        }
    }

    private fun extractId3Canvas(data: ByteArray): EmbeddedCanvas? {
        if (data.size < ID3_HEADER_SIZE) return null
        val tagEnd = id3TagSize(data.copyOfRange(0, ID3_HEADER_SIZE)).coerceAtMost(data.size)
        var offset = ID3_HEADER_SIZE
        var provider = "Embedded"

        while (offset + ID3_FRAME_HEADER_SIZE <= tagEnd) {
            val id = data.copyOfRange(offset, offset + 4).toString(StandardCharsets.US_ASCII)
            if (id.all { it == '\u0000' }) break
            val size = data.readUInt32(offset + 4).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val payloadStart = offset + ID3_FRAME_HEADER_SIZE
            val payloadEnd = payloadStart + size
            if (size <= 0 || payloadEnd > tagEnd) break
            val payload = data.copyOfRange(payloadStart, payloadEnd)

            if (id == "TXXX") {
                readId3UserText(payload)?.let { (description, value) ->
                    if (description == METROFUSE_CANVAS_PROVIDER && value.isNotBlank()) {
                        provider = value
                    }
                }
            } else if (id == "GEOB") {
                readId3GeobCanvas(payload, provider)?.let { return it }
            }
            offset = payloadEnd
        }
        return null
    }

    private fun readId3GeobCanvas(
        payload: ByteArray,
        provider: String,
    ): EmbeddedCanvas? {
        if (payload.isEmpty()) return null
        var offset = 1
        val mimeEnd = payload.indexOfZero(offset).takeIf { it >= 0 } ?: return null
        val mimeType = payload.copyOfRange(offset, mimeEnd).toString(StandardCharsets.ISO_8859_1)
        offset = mimeEnd + 1
        val fileNameEnd = payload.indexOfZero(offset).takeIf { it >= 0 } ?: return null
        offset = fileNameEnd + 1
        val descriptionEnd = payload.indexOfZero(offset).takeIf { it >= 0 } ?: return null
        val description = payload.copyOfRange(offset, descriptionEnd).toString(StandardCharsets.ISO_8859_1)
        if (description != METROFUSE_CANVAS_DESCRIPTION) return null
        val bytes = payload.copyOfRange(descriptionEnd + 1, payload.size)
        return bytes.takeIf { it.isNotEmpty() }?.let {
            EmbeddedCanvas(
                mimeType = mimeType.ifBlank { "video/mp4" },
                bytes = it,
                provider = provider,
            )
        }
    }

    private fun readId3UserText(payload: ByteArray): Pair<String, String>? {
        if (payload.isEmpty()) return null
        val encoding = payload[0]
        val content = payload.copyOfRange(1, payload.size)
        return if (encoding == TEXT_ENCODING_UTF16) {
            val separator = content.indexOfUtf16Terminator()
            if (separator < 0) return null
            val description = decodeUtf16(content.copyOfRange(0, separator))
            val value = decodeUtf16(content.copyOfRange(separator + 2, content.size))
            description to value
        } else {
            val separator = content.indexOfZero(0)
            if (separator < 0) return null
            content.copyOfRange(0, separator).toString(StandardCharsets.ISO_8859_1) to
                content.copyOfRange(separator + 1, content.size).toString(StandardCharsets.ISO_8859_1)
        }
    }

    private fun extractFlacCanvas(input: InputStream): EmbeddedCanvas? {
        val marker = input.readExactlyOrNull(4) ?: return null
        if (!marker.contentEquals("fLaC".toByteArray(StandardCharsets.US_ASCII))) return null
        while (true) {
            val header = input.readExactlyOrNull(4) ?: return null
            val isLast = header[0].toInt() and 0x80 != 0
            val type = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)
            val payload = input.readExactlyOrNull(length) ?: return null
            if (type == FLAC_VORBIS_COMMENT) {
                return readVorbisCanvas(payload)
            }
            if (isLast) break
        }
        return null
    }

    private fun readVorbisCanvas(data: ByteArray): EmbeddedCanvas? {
        if (data.size < 8) return null
        var offset = 0
        val vendorLength = data.readInt32LittleEndian(offset)
        offset += 4 + vendorLength
        if (offset + 4 > data.size) return null
        val count = data.readInt32LittleEndian(offset)
        offset += 4
        var mimeType = "video/mp4"
        var provider = "Embedded"
        var encodedCanvas: String? = null

        repeat(count) {
            if (offset + 4 > data.size) return@repeat
            val length = data.readInt32LittleEndian(offset)
            offset += 4
            if (length < 0 || offset + length > data.size) return@repeat
            val comment = data.copyOfRange(offset, offset + length).toString(StandardCharsets.UTF_8)
            offset += length
            val key = comment.substringBefore("=", "").uppercase(Locale.US)
            val value = comment.substringAfter("=", "")
            when (key) {
                METROFUSE_CANVAS_MIME -> mimeType = value.ifBlank { mimeType }
                METROFUSE_CANVAS_PROVIDER -> provider = value.ifBlank { provider }
                METROFUSE_CANVAS_BASE64 -> encodedCanvas = value
            }
        }

        val bytes = encodedCanvas?.takeIf { it.isNotBlank() }?.let { Base64.getDecoder().decode(it) } ?: return null
        return EmbeddedCanvas(mimeType = mimeType, bytes = bytes, provider = provider)
    }

    private fun extractMp4Canvas(data: ByteArray): EmbeddedCanvas? {
        val moov = parseMp4Boxes(data, 0, data.size).firstOrNull { it.type.contentEquals(MP4_MOOV) } ?: return null
        val udta = parseMp4Boxes(data, moov.payloadStart, moov.end).firstOrNull { it.type.contentEquals(MP4_UDTA) } ?: return null
        val meta = parseMp4Boxes(data, udta.payloadStart, udta.end).firstOrNull { it.type.contentEquals(MP4_META) } ?: return null
        val ilst = parseMp4Boxes(data, meta.payloadStart + 4, meta.end).firstOrNull { it.type.contentEquals(MP4_ILST) } ?: return null

        var mimeType = "video/mp4"
        var provider = "Embedded"
        var canvasBytes: ByteArray? = null
        parseMp4Boxes(data, ilst.payloadStart, ilst.end)
            .filter { it.type.contentEquals(MP4_FREEFORM) }
            .forEach { freeform ->
                val item = readMp4FreeformItem(data, freeform)
                if (item.mean != METROFUSE_MP4_MEAN) return@forEach
                when (item.name) {
                    METROFUSE_MP4_CANVAS_NAME -> canvasBytes = item.data
                    METROFUSE_MP4_CANVAS_MIME_NAME -> mimeType = item.data.toString(StandardCharsets.UTF_8).ifBlank { mimeType }
                    METROFUSE_MP4_CANVAS_PROVIDER_NAME -> provider = item.data.toString(StandardCharsets.UTF_8).ifBlank { provider }
                }
            }

        return canvasBytes?.takeIf { it.isNotEmpty() }?.let {
            EmbeddedCanvas(mimeType = mimeType, bytes = it, provider = provider)
        }
    }

    private fun readMp4FreeformItem(
        data: ByteArray,
        freeform: Mp4Box,
    ): Mp4FreeformItem {
        var mean = ""
        var name = ""
        var value = ByteArray(0)
        parseMp4Boxes(data, freeform.payloadStart, freeform.end).forEach { child ->
            when {
                child.type.contentEquals(MP4_MEAN) && child.payloadStart + 4 <= child.end ->
                    mean = data.copyOfRange(child.payloadStart + 4, child.end).toString(StandardCharsets.UTF_8)
                child.type.contentEquals(MP4_NAME) && child.payloadStart + 4 <= child.end ->
                    name = data.copyOfRange(child.payloadStart + 4, child.end).toString(StandardCharsets.UTF_8)
                child.type.contentEquals(MP4_DATA) && child.payloadStart + 8 <= child.end ->
                    value = data.copyOfRange(child.payloadStart + 8, child.end)
            }
        }
        return Mp4FreeformItem(mean, name, value)
    }

    private fun extractHlsCanvasPackage(
        context: Context,
        trackUri: String,
        bytes: ByteArray,
    ): String? {
        val targetDir = context.cacheDir
            .resolve("embedded-canvas")
            .resolve(stableDigest(trackUri))
            .apply {
                deleteRecursively()
                mkdirs()
            }

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || entry.name.contains("..")) {
                    zip.closeEntry()
                    continue
                }
                val outFile = targetDir.resolve(entry.name)
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { output -> zip.copyTo(output) }
                zip.closeEntry()
            }
        }

        val manifest = targetDir.resolve(METROFUSE_HLS_MANIFEST_NAME).takeIf { it.exists() } ?: return null
        return manifest.toUri().toString()
    }

    private fun cachedCanvasFile(
        context: Context,
        trackUri: String,
        mimeType: String,
    ): File =
        context.cacheDir
            .resolve("embedded-canvas")
            .resolve("${stableDigest(trackUri)}.${canvasExtension(mimeType)}")

    private fun stableDigest(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun canvasFileName(mimeType: String): String =
        "canvas.${canvasExtension(mimeType)}"

    private fun canvasExtension(mimeType: String): String =
        when (mimeType.lowercase(Locale.US)) {
            METROFUSE_HLS_CANVAS_MIME -> "m3u8.zip"
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            else -> "mp4"
        }

    private fun ByteArray.indexOfZero(start: Int): Int {
        for (index in start until size) {
            if (this[index] == 0.toByte()) return index
        }
        return -1
    }

    private fun ByteArray.indexOfUtf16Terminator(): Int {
        var index = 0
        while (index + 1 < size) {
            if (this[index] == 0.toByte() && this[index + 1] == 0.toByte()) return index
            index += 2
        }
        return -1
    }

    private fun decodeUtf16(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
        }
        return bytes.toString(Charsets.UTF_16)
    }

    private fun ByteArray.readInt32LittleEndian(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.isMp4ContainerType(): Boolean =
        MP4_CONTAINER_TYPES.any { it.contentEquals(this) }

    private fun replaceFile(
        source: File,
        target: File,
    ) {
        if (source.renameTo(target)) return
        if (!target.delete() || !source.renameTo(target)) {
            source.delete()
            error("Could not replace tagged audio file")
        }
    }

    private data class FlacBlock(
        val type: Int,
        val data: ByteArray,
    )

    private data class Mp4Box(
        val type: ByteArray,
        val start: Int,
        val headerSize: Int,
        val size: Int,
    ) {
        val payloadStart: Int
            get() = start + headerSize
        val end: Int
            get() = start + size
    }

    private data class Mp4FreeformItem(
        val mean: String,
        val name: String,
        val data: ByteArray,
    )

    private const val ID3_HEADER_SIZE = 10
    private const val ID3_FRAME_HEADER_SIZE = 10
    private const val ID3_PADDING_BYTES = 2048
    private const val TEXT_ENCODING_UTF16: Byte = 1
    private const val FLAC_STREAMINFO = 0
    private const val FLAC_VORBIS_COMMENT = 4
    private const val FLAC_PICTURE = 6
    private const val MP4_BOX_HEADER_SIZE = 8
    private const val MP4_EXTENDED_BOX_HEADER_SIZE = 16
    private const val MP4_DATA_BINARY = 0
    private const val MP4_DATA_UTF8 = 1
    private const val MP4_DATA_JPEG = 13
    private const val MP4_DATA_PNG = 14
    private const val METROFUSE_CANVAS_DESCRIPTION = "METROFUSE_CANVAS"
    private const val METROFUSE_CANVAS_BASE64 = "METROFUSE_CANVAS_BASE64"
    private const val METROFUSE_CANVAS_MIME = "METROFUSE_CANVAS_MIME"
    private const val METROFUSE_CANVAS_PROVIDER = "METROFUSE_CANVAS_PROVIDER"
    internal const val METROFUSE_HLS_CANVAS_MIME = "application/vnd.metrofuse.canvas-hls"
    private const val METROFUSE_HLS_MANIFEST_NAME = "manifest.m3u8"
    private const val METROFUSE_MP4_MEAN = "com.metrofuse.music"
    private const val METROFUSE_MP4_CANVAS_NAME = "CANVAS"
    private const val METROFUSE_MP4_CANVAS_MIME_NAME = "CANVAS_MIME"
    private const val METROFUSE_MP4_CANVAS_PROVIDER_NAME = "CANVAS_PROVIDER"
    private val MP4_FTYP = asciiType("ftyp")
    private val MP4_MOOV = asciiType("moov")
    private val MP4_MDAT = asciiType("mdat")
    private val MP4_UDTA = asciiType("udta")
    private val MP4_META = asciiType("meta")
    private val MP4_HDLR = asciiType("hdlr")
    private val MP4_ILST = asciiType("ilst")
    private val MP4_FREEFORM = asciiType("----")
    private val MP4_MEAN = asciiType("mean")
    private val MP4_NAME = asciiType("name")
    private val MP4_DATA = asciiType("data")
    private val MP4_MDIR = asciiType("mdir")
    private val MP4_STCO = asciiType("stco")
    private val MP4_CO64 = asciiType("co64")
    private val MP4_TITLE = byteArrayOf(0xA9.toByte(), 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte())
    private val MP4_ARTIST = byteArrayOf(0xA9.toByte(), 'A'.code.toByte(), 'R'.code.toByte(), 'T'.code.toByte())
    private val MP4_ALBUM = byteArrayOf(0xA9.toByte(), 'a'.code.toByte(), 'l'.code.toByte(), 'b'.code.toByte())
    private val MP4_YEAR = byteArrayOf(0xA9.toByte(), 'd'.code.toByte(), 'a'.code.toByte(), 'y'.code.toByte())
    private val MP4_LYRICS = byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())
    private val MP4_COVER = asciiType("covr")
    private val MP4_REPLACED_ITEM_TYPES = listOf(MP4_TITLE, MP4_ARTIST, MP4_ALBUM, MP4_YEAR, MP4_LYRICS, MP4_COVER)
    private val MP4_CONTAINER_TYPES = listOf(
        asciiType("moov"),
        asciiType("trak"),
        asciiType("mdia"),
        asciiType("minf"),
        asciiType("stbl"),
        asciiType("edts"),
        asciiType("dinf"),
        asciiType("udta"),
    )

    private fun asciiType(value: String): ByteArray =
        value.toByteArray(StandardCharsets.US_ASCII)
}
