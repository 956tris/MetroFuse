/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.metrolist.music.R
import com.metrolist.music.constants.AudioProviderOrderItem
import com.metrolist.music.constants.SpotifyCookieKey
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.providers.ProviderMatchSearch
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.spotify.SpotifyCanvasClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ShareSongLinkDialog(
    mediaMetadata: MediaMetadata,
    onDismiss: () -> Unit,
    onShared: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loadingService by remember { mutableStateOf<ShareLinkService?>(null) }

    DefaultDialog(
        onDismiss = onDismiss,
        title = { Text(stringResource(R.string.share_song_link)) },
        buttons = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
        horizontalAlignment = androidx.compose.ui.Alignment.Start,
    ) {
        ShareLinkService.entries.forEach { service ->
            ListItem(
                headlineContent = { Text(service.title) },
                leadingContent = {
                    Icon(
                        painter = painterResource(service.iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                },
                trailingContent = {
                    if (loadingService == service) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = loadingService == null) {
                            scope.launch {
                                loadingService = service
                                val url =
                                    runCatching {
                                        resolveShareUrl(context, mediaMetadata, service)
                                    }.getOrNull()
                                loadingService = null

                                if (url.isNullOrBlank()) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.share_song_link_failed),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                } else {
                                    shareText(context, url)
                                    onShared()
                                    onDismiss()
                                }
                            }
                        },
            )
        }
    }
}

private enum class ShareLinkService(
    val title: String,
    val iconRes: Int,
    val provider: AudioProviderOrderItem?,
) {
    YOUTUBE_MUSIC("YouTube Music", R.drawable.provider_youtube_music, AudioProviderOrderItem.YOUTUBE_MUSIC),
    SPOTIFY("Spotify", R.drawable.provider_spotify, null),
    TIDAL("TIDAL", R.drawable.provider_tidal, AudioProviderOrderItem.TIDAL),
    SOUNDCLOUD("SoundCloud", R.drawable.provider_soundcloud, AudioProviderOrderItem.SOUNDCLOUD),
    DEEZER("Deezer", R.drawable.provider_deezer, AudioProviderOrderItem.DEEZER),
    APPLE_MUSIC("Apple Music", R.drawable.music_note, AudioProviderOrderItem.APPLE_MUSIC),
    QOBUZ("Qobuz", R.drawable.music_note, AudioProviderOrderItem.QOBUZ),
}

private suspend fun resolveShareUrl(
    context: Context,
    metadata: MediaMetadata,
    service: ShareLinkService,
): String =
    withContext(Dispatchers.IO) {
        metadata.directShareUrl(service)?.let { return@withContext it }

        when (service) {
            ShareLinkService.SPOTIFY -> {
                val query = metadata.shareSearchQuery()
                val cookie = context.dataStore.get(SpotifyCookieKey, "")
                val resolved =
                    cookie
                        .takeIf { it.isNotBlank() }
                        ?.let { SpotifyCanvasClient.resolveSearch(query, it) }
                        ?.spotifyTrackIdOrNull()
                        ?.let { "https://open.spotify.com/track/$it" }
                resolved ?: "https://open.spotify.com/search/${query.urlPathEncoded()}"
            }
            ShareLinkService.YOUTUBE_MUSIC ->
                "https://music.youtube.com/search?q=${metadata.shareSearchQuery().urlQueryEncoded()}"
            else -> {
                val provider = service.provider ?: return@withContext service.searchUrl(metadata)
                ProviderMatchSearch
                    .searchProvider(
                        context = context,
                        metadata = metadata,
                        provider = provider,
                        limit = 1,
                    ).firstOrNull()
                    ?.shareUrl
                    ?: service.searchUrl(metadata)
            }
        }
    }

private fun MediaMetadata.directShareUrl(service: ShareLinkService): String? =
    when (service) {
        ShareLinkService.YOUTUBE_MUSIC ->
            id.youtubeVideoIdOrNull()?.let { "https://music.youtube.com/watch?v=$it" }
        ShareLinkService.SPOTIFY ->
            id.spotifyTrackIdOrNull()?.let { "https://open.spotify.com/track/$it" }
        ShareLinkService.TIDAL ->
            id.providerNumericId("tidal")?.let { "https://listen.tidal.com/track/$it" }
        ShareLinkService.SOUNDCLOUD ->
            id.takeIf { it.startsWith("https://soundcloud.com/", ignoreCase = true) }
        ShareLinkService.DEEZER ->
            id.providerNumericId("deezer")?.let { "https://www.deezer.com/track/$it" }
        ShareLinkService.APPLE_MUSIC ->
            id.takeIf { it.startsWith("https://music.apple.com/", ignoreCase = true) }
        ShareLinkService.QOBUZ ->
            id.providerNumericId("qobuz")?.let { "https://open.qobuz.com/track/$it" }
    }

private fun ShareLinkService.searchUrl(metadata: MediaMetadata): String {
    val query = metadata.shareSearchQuery()
    return when (this) {
        ShareLinkService.YOUTUBE_MUSIC -> "https://music.youtube.com/search?q=${query.urlQueryEncoded()}"
        ShareLinkService.SPOTIFY -> "https://open.spotify.com/search/${query.urlPathEncoded()}"
        ShareLinkService.TIDAL -> "https://listen.tidal.com/search?q=${query.urlQueryEncoded()}"
        ShareLinkService.SOUNDCLOUD -> "https://soundcloud.com/search/sounds?q=${query.urlQueryEncoded()}"
        ShareLinkService.DEEZER -> "https://www.deezer.com/search/${query.urlPathEncoded()}"
        ShareLinkService.APPLE_MUSIC -> "https://music.apple.com/search?term=${query.urlQueryEncoded()}"
        ShareLinkService.QOBUZ -> "https://www.qobuz.com/search?q=${query.urlQueryEncoded()}"
    }
}

private fun MediaMetadata.shareSearchQuery(): String =
    listOf(
        title,
        artists.joinToString(" ") { it.name },
        album?.title.orEmpty(),
    ).filter { it.isNotBlank() }
        .joinToString(" ")

private fun String.youtubeVideoIdOrNull(): String? {
    val trimmed = trim()
    if (trimmed.startsWith("http", ignoreCase = true)) {
        val uri = runCatching { trimmed.toUri() }.getOrNull() ?: return null
        if (!uri.host.orEmpty().contains("youtu", ignoreCase = true)) return null
        return uri.getQueryParameter("v")
            ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
    }
    if (trimmed.contains(":") || trimmed.contains("/") || trimmed.startsWith("http", ignoreCase = true)) return null
    return trimmed.takeIf { it.length in 8..64 }
}

private fun String.spotifyTrackIdOrNull(): String? {
    val trimmed = trim()
    Regex("""spotify:track:([A-Za-z0-9]{22})""", RegexOption.IGNORE_CASE)
        .find(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { return it }
    Regex("""open\.spotify\.com/track/([A-Za-z0-9]{22})""", RegexOption.IGNORE_CASE)
        .find(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { return it }
    return null
}

private fun String.providerNumericId(provider: String): String? {
    val trimmed = trim()
    Regex("""$provider:track:(\d+)""", RegexOption.IGNORE_CASE)
        .find(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { return it }
    Regex("""(?:(?:www\.)?$provider\.com|listen\.$provider\.com|open\.$provider\.com)/(?:track|tracks)/(\d+)""", RegexOption.IGNORE_CASE)
        .find(trimmed)
        ?.groupValues
        ?.getOrNull(1)
        ?.let { return it }
    return null
}

private fun String.urlQueryEncoded(): String = java.net.URLEncoder.encode(this, Charsets.UTF_8.name())

private fun String.urlPathEncoded(): String = urlQueryEncoded().replace("+", "%20")

private fun shareText(
    context: Context,
    url: String,
) {
    val intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
    context.startActivity(Intent.createChooser(intent, null))
}
