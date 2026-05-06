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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import com.metrolist.music.constants.PreferSoundCloudAudioKey
import com.metrolist.music.constants.SoundCloudAuthTokenKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.InfoLabel
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.ui.utils.backToMain
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
    val (preferSoundCloudAudio, onPreferSoundCloudAudioChange) =
        rememberPreference(PreferSoundCloudAudioKey, defaultValue = false)
    var showAuthDialog by rememberSaveable { mutableStateOf(false) }

    if (showAuthDialog) {
        TextFieldDialog(
            onDismiss = { showAuthDialog = false },
            icon = { Icon(painterResource(R.drawable.token), contentDescription = null) },
            title = { Text(stringResource(R.string.soundcloud_auth_token_title)) },
            initialTextFieldValue = TextFieldValue(soundCloudAuthToken),
            placeholder = { Text(stringResource(R.string.soundcloud_auth_token_placeholder)) },
            singleLine = false,
            isInputValid = { value ->
                value.isBlank() || normalizeSoundCloudAuthInput(value) != null
            },
            onDone = { value ->
                soundCloudAuthToken = normalizeSoundCloudAuthInput(value).orEmpty()
                showAuthDialog = false
            },
            extraContent = {
                InfoLabel(text = stringResource(R.string.soundcloud_auth_token_helper))
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
            title = stringResource(R.string.general),
            items =
                listOf(
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.soundcloud_web_login)) },
                        description = {
                            Text(
                                if (authConfigured) {
                                    stringResource(R.string.soundcloud_auth_configured)
                                } else {
                                    stringResource(R.string.soundcloud_auth_not_configured)
                                },
                            )
                        },
                        icon = painterResource(R.drawable.login),
                        onClick = {
                            navController.navigate("settings/integrations/soundcloud/login")
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.soundcloud_auth_token_title)) },
                        description = { Text(stringResource(R.string.soundcloud_auth_token_helper)) },
                        icon = painterResource(R.drawable.token),
                        onClick = { showAuthDialog = true },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.prefer_soundcloud_audio)) },
                        description = { Text(stringResource(R.string.prefer_soundcloud_audio_desc)) },
                        trailingContent = {
                            Switch(
                                checked = preferSoundCloudAudio,
                                onCheckedChange = onPreferSoundCloudAudioChange,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (preferSoundCloudAudio) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        icon = painterResource(R.drawable.cloud),
                        onClick = { onPreferSoundCloudAudioChange(!preferSoundCloudAudio) },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.soundcloud_clear_auth)) },
                        description = {
                            Text(
                                if (authConfigured) {
                                    stringResource(R.string.soundcloud_auth_configured)
                                } else {
                                    stringResource(R.string.soundcloud_auth_not_configured)
                                },
                            )
                        },
                        icon = painterResource(R.drawable.delete),
                        enabled = authConfigured,
                        onClick = {
                            soundCloudAuthToken = ""
                        },
                    ),
                ),
        )

        Spacer(Modifier.height(8.dp))
        InfoLabel(text = stringResource(R.string.soundcloud_web_login_desc))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.soundcloud_integration)) },
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
