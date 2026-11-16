/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.local

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import coil3.ImageLoader
import coil3.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.toAndroidUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Buffer
import java.io.FileNotFoundException
import android.net.Uri as AndroidUri

private const val LOCAL_ARTWORK_SCHEME = "metrofuse-local-artwork"

// MediaStore album IDs can group unrelated files; each file revision needs its own artwork key.
internal fun localAudioArtworkUri(
    audioUri: AndroidUri,
    dateModifiedSeconds: Long?,
): String =
    AndroidUri
        .Builder()
        .scheme(LOCAL_ARTWORK_SCHEME)
        .authority("audio")
        .appendQueryParameter("uri", audioUri.toString())
        .apply {
            dateModifiedSeconds?.let { appendQueryParameter("modified", it.toString()) }
        }.build()
        .toString()

internal fun localAudioArtworkSource(artworkUri: AndroidUri): AndroidUri? {
    if (artworkUri.scheme != LOCAL_ARTWORK_SCHEME || artworkUri.authority != "audio") return null
    val audioUri = artworkUri.getQueryParameter("uri")?.let(AndroidUri::parse) ?: return null
    return audioUri.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT || it.scheme == ContentResolver.SCHEME_FILE }
}

internal class LocalAudioArtworkFetcher(
    private val audioUri: AndroidUri,
    private val options: Options,
    private val readEmbeddedPicture: (Context, AndroidUri) -> ByteArray?,
) : Fetcher {
    override suspend fun fetch(): SourceFetchResult =
        withContext(Dispatchers.IO) {
            val bytes =
                readEmbeddedPicture(options.context, audioUri)?.takeIf { it.isNotEmpty() }
                    ?: throw FileNotFoundException("Local audio has no embedded artwork")
            SourceFetchResult(
                source = ImageSource(Buffer().write(bytes), options.fileSystem),
                mimeType = null,
                dataSource = DataSource.DISK,
            )
        }

    class Factory(
        private val readEmbeddedPicture: (Context, AndroidUri) -> ByteArray? = ::readLocalEmbeddedPicture,
    ) : Fetcher.Factory<Uri> {
        override fun create(
            data: Uri,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher? {
            val audioUri = localAudioArtworkSource(data.toAndroidUri()) ?: return null
            return LocalAudioArtworkFetcher(audioUri, options, readEmbeddedPicture)
        }
    }
}

private fun readLocalEmbeddedPicture(
    context: Context,
    audioUri: AndroidUri,
): ByteArray? {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(context, audioUri)
        return retriever.embeddedPicture
    } finally {
        retriever.release()
    }
}
