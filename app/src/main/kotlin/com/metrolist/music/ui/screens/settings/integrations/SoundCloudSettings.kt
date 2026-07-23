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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.SoundCloudAudioQuality
import com.metrolist.music.constants.SoundCloudAudioQualityKey
import com.metrolist.music.constants.SoundCloudAudioQualityOptions
import com.metrolist.music.constants.SoundCloudAuthTokenKey
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.InfoLabel
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.soundcloud.isSoundCloudAuthConfigured
import com.metrolist.music.utils.soundcloud.normalizeSoundCloudAuthInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundCloudSettings(
    navController: NavController,
) {
    var soundCloudAuthToken by rememberPreference(SoundCloudAuthTokenKey, "")
    val authConfigured = isSoundCloudAuthConfigured(soundCloudAuthToken)
    var soundCloudAudioQuality by rememberEnumPreference(SoundCloudAudioQualityKey, SoundCloudAudioQuality.AAC_160)
    var showAuthDialog by rememberSaveable { mutableStateOf(false) }
    var showQualityDialog by rememberSaveable { mutableStateOf(false) }

    if (showAuthDialog) {
        TextFieldDialog(
            onDismiss = { showAuthDialog = false },
            icon = { Icon(painterResource(com.metrolist.music.R.drawable.token), contentDescription = null) },
            title = { Text(stringResource(com.metrolist.music.R.string.soundcloud_auth_token_title)) },
            initialTextFieldValue = TextFieldValue(soundCloudAuthToken),
            placeholder = { Text(stringResource(com.metrolist.music.R.string.soundcloud_auth_token_placeholder)) },
            singleLine = false,
            isInputValid = { value ->
                value.isBlank() || normalizeSoundCloudAuthInput(value) != null
            },
            onDone = { value ->
                soundCloudAuthToken = normalizeSoundCloudAuthInput(value).orEmpty()
                showAuthDialog = false
            },
            extraContent = {
                InfoLabel(text = stringResource(com.metrolist.music.R.string.soundcloud_auth_token_helper))
            },
        )
    }

    if (showQualityDialog) {
        EnumDialog(
            onDismiss = { showQualityDialog = false },
            title = stringResource(com.metrolist.music.R.string.soundcloud_audio_quality),
            values = SoundCloudAudioQualityOptions,
            current = soundCloudAudioQuality,
            onSelect = {
                soundCloudAudioQuality = it
                showQualityDialog = false
            },
            valueText = { quality ->
                when (quality) {
                    SoundCloudAudioQuality.MP3_128 -> stringResource(com.metrolist.music.R.string.soundcloud_quality_mp3_128)
                    SoundCloudAudioQuality.AAC_96 -> stringResource(com.metrolist.music.R.string.soundcloud_quality_aac_96)
                    SoundCloudAudioQuality.AAC_160 -> stringResource(com.metrolist.music.R.string.soundcloud_quality_aac_160)
                }
            },
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
            title = stringResource(com.metrolist.music.R.string.general),
            items =
                listOf(
                    Material3SettingsItem(
                        title = { Text(stringResource(com.metrolist.music.R.string.soundcloud_web_login)) },
                        description = {
                            Text(
                                if (authConfigured) {
                                    stringResource(com.metrolist.music.R.string.soundcloud_auth_configured)
                                } else {
                                    stringResource(com.metrolist.music.R.string.soundcloud_auth_not_configured)
                                },
                            )
                        },
                        icon = painterResource(com.metrolist.music.R.drawable.login),
                        onClick = {
                            navController.navigate("settings/integrations/soundcloud/login")
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(com.metrolist.music.R.string.soundcloud_auth_token_title)) },
                        description = { Text(stringResource(com.metrolist.music.R.string.soundcloud_auth_token_helper)) },
                        icon = painterResource(com.metrolist.music.R.drawable.token),
                        onClick = { showAuthDialog = true },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(com.metrolist.music.R.string.soundcloud_audio_quality)) },
                        description = {
                            Text(
                                when (soundCloudAudioQuality) {
                                    SoundCloudAudioQuality.MP3_128 -> stringResource(com.metrolist.music.R.string.soundcloud_quality_mp3_128)
                                    SoundCloudAudioQuality.AAC_96 -> stringResource(com.metrolist.music.R.string.soundcloud_quality_aac_96)
                                    SoundCloudAudioQuality.AAC_160 -> stringResource(com.metrolist.music.R.string.soundcloud_quality_aac_160)
                                },
                            )
                        },
                        icon = painterResource(com.metrolist.music.R.drawable.tune),
                        onClick = { showQualityDialog = true },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(com.metrolist.music.R.string.soundcloud_clear_auth)) },
                        description = {
                            Text(
                                if (authConfigured) {
                                    stringResource(com.metrolist.music.R.string.soundcloud_auth_configured)
                                } else {
                                    stringResource(com.metrolist.music.R.string.soundcloud_auth_not_configured)
                                },
                            )
                        },
                        icon = painterResource(com.metrolist.music.R.drawable.delete),
                        enabled = authConfigured,
                        onClick = {
                            soundCloudAuthToken = ""
                        },
                    ),
                ),
        )

        Spacer(Modifier.height(8.dp))
        InfoLabel(text = stringResource(com.metrolist.music.R.string.soundcloud_web_login_desc))
    }

    TopAppBar(
        title = { Text(stringResource(com.metrolist.music.R.string.soundcloud_integration)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(com.metrolist.music.R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}
