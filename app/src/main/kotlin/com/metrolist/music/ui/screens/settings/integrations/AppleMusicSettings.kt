/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.AppleMusicAudioQuality
import com.metrolist.music.constants.AppleMusicAudioQualityKey
import com.metrolist.music.constants.AppleMusicAudioQualityOptions
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.InfoLabel
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberEnumPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppleMusicSettings(
    navController: NavController,
) {
    var audioQuality by rememberEnumPreference(AppleMusicAudioQualityKey, AppleMusicAudioQuality.AAC)
    var showQualityDialog by rememberSaveable { mutableStateOf(false) }

    val qualityAac = stringResource(R.string.apple_music_quality_aac)
    val qualityAlac = stringResource(R.string.apple_music_quality_alac)
    val qualityAtmos = stringResource(R.string.apple_music_quality_atmos)
    val qualityAc3 = stringResource(R.string.apple_music_quality_ac3)
    fun qualityLabel(quality: AppleMusicAudioQuality): String =
        when (quality) {
            AppleMusicAudioQuality.AAC -> qualityAac
            AppleMusicAudioQuality.ALAC -> qualityAlac
            AppleMusicAudioQuality.DOLBY_ATMOS -> qualityAtmos
            AppleMusicAudioQuality.AC3 -> qualityAc3
        }

    if (showQualityDialog) {
        EnumDialog(
            onDismiss = { showQualityDialog = false },
            onSelect = {
                audioQuality = it
                showQualityDialog = false
            },
            title = stringResource(R.string.apple_music_quality_title),
            current = audioQuality,
            values = AppleMusicAudioQualityOptions,
            valueText = { qualityLabel(it) },
        )
    }

    Column(
        modifier =
            Modifier
                .windowInsetsPadding(
                    LocalPlayerAwareWindowInsets.current.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                    ),
                ).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
            ),
        )

        Material3SettingsGroup(
            title = stringResource(R.string.general),
            items =
                listOf(
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.apple_music_quality_title)) },
                        description = { Text(qualityLabel(audioQuality)) },
                        icon = painterResource(R.drawable.settings),
                        onClick = { showQualityDialog = true },
                    ),
                ),
        )

        Spacer(Modifier.height(8.dp))
        InfoLabel(text = stringResource(R.string.apple_music_quality_desc))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.apple_music_integration)) },
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
