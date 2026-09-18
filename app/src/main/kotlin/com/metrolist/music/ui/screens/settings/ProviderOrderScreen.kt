/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.AudioProviderDisabledKey
import com.metrolist.music.constants.AudioProviderOrder
import com.metrolist.music.constants.AudioProviderOrderItem
import com.metrolist.music.constants.AudioProviderOrderKey
import com.metrolist.music.constants.deserializeDisabledProviders
import com.metrolist.music.constants.serializeDisabledProviders
import com.metrolist.music.ui.component.DraggableLyricsProviderItem
import com.metrolist.music.ui.component.DraggableLyricsProviderList
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderOrderScreen(
    navController: NavController,
) {
    val (providerOrder, onProviderOrderChange) = rememberPreference(
        key = AudioProviderOrderKey,
        defaultValue = AudioProviderOrder.serialize(AudioProviderOrder.Default),
    )
    val (disabledRaw, onDisabledChange) = rememberPreference(
        key = AudioProviderDisabledKey,
        defaultValue = "",
    )
    val disabled = deserializeDisabledProviders(disabledRaw)
    fun setProviderEnabled(provider: AudioProviderOrderItem, enabled: Boolean) {
        onDisabledChange(
            serializeDisabledProviders(
                if (enabled) disabled - provider else disabled + provider,
            ),
        )
    }
    val normalizedOrder = AudioProviderOrder.deserialize(providerOrder)
    val draggableItems = remember { mutableStateListOf<DraggableLyricsProviderItem>() }
    val providerIcon = painterResource(R.drawable.music_note)
    val offlineName = stringResource(R.string.audio_provider_offline)
    val soundCloudName = stringResource(R.string.audio_provider_soundcloud)
    val tidalName = stringResource(R.string.audio_provider_tidal)
    val deezerName = stringResource(R.string.audio_provider_deezer)
    val jiosaavnName = stringResource(R.string.audio_provider_jiosaavn)
    val youtubeMusicName = stringResource(R.string.audio_provider_youtube_music)
    val qobuzName = stringResource(R.string.audio_provider_qobuz)
    val appleMusicName = stringResource(R.string.audio_provider_apple_music)

    LaunchedEffect(
        providerOrder,
        disabledRaw,
        offlineName,
        soundCloudName,
        tidalName,
        deezerName,
        jiosaavnName,
        youtubeMusicName,
        qobuzName,
        appleMusicName,
    ) {
        draggableItems.clear()
        draggableItems.addAll(
            normalizedOrder.map { provider ->
                DraggableLyricsProviderItem(
                    id = provider.name,
                    name = when (provider) {
                        AudioProviderOrderItem.OFFLINE -> offlineName
                        AudioProviderOrderItem.SOUNDCLOUD -> soundCloudName
                        AudioProviderOrderItem.TIDAL -> tidalName
                        AudioProviderOrderItem.DEEZER -> deezerName
                        AudioProviderOrderItem.JIOSAAVN -> jiosaavnName
                        AudioProviderOrderItem.YOUTUBE_MUSIC -> youtubeMusicName
                        AudioProviderOrderItem.QOBUZ -> qobuzName
                        AudioProviderOrderItem.APPLE_MUSIC -> appleMusicName
                    },
                    icon = providerIcon,
                    enabled = provider !in disabled,
                    onToggleEnabled = {
                        setProviderEnabled(provider, provider in disabled)
                    },
                )
            },
        )
    }

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .padding(horizontal = 16.dp),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
            ),
        )

        Text(
            text = stringResource(R.string.provider_order_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
        )

        DraggableLyricsProviderList(
            items = draggableItems,
            onItemsReordered = { reorderedItems ->
                val reorderedProviders = reorderedItems.mapNotNull { item ->
                    AudioProviderOrderItem.entries.find { it.name == item.id }
                }
                onProviderOrderChange(AudioProviderOrder.serialize(reorderedProviders))
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        TextButton(
            onClick = {
                onProviderOrderChange(AudioProviderOrder.serialize(AudioProviderOrder.Default))
                onDisabledChange("")
            },
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.refresh),
                contentDescription = null,
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text(stringResource(R.string.provider_order_reset))
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.provider_order)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}
