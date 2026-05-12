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
import com.metrolist.music.constants.DeezerAudioQuality
import com.metrolist.music.constants.DeezerAudioQualityKey
import com.metrolist.music.constants.DeezerAudioQualityOptions
import com.metrolist.music.constants.DeezerCookieKey
import com.metrolist.music.constants.DeezerResolverUrlKey
import com.metrolist.music.constants.PreferDeezerAudioKey
import com.metrolist.music.deezer.DeezerAudioProvider
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.InfoLabel
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.deezer.isDeezerCookieConfigured
import com.metrolist.music.utils.deezer.normalizeDeezerCookieInput
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeezerSettings(
    navController: NavController,
) {
    val (preferDeezerAudio, onPreferDeezerAudioChange) =
        rememberPreference(PreferDeezerAudioKey, false)
    var resolverUrl by rememberPreference(DeezerResolverUrlKey, DeezerAudioProvider.DEFAULT_RESOLVER_URL)
    var audioQuality by rememberEnumPreference(DeezerAudioQualityKey, DeezerAudioQuality.MP3_128)
    var deezerCookie by rememberPreference(DeezerCookieKey, "")
    val cookieConfigured = isDeezerCookieConfigured(deezerCookie)
    var showResolverDialog by rememberSaveable { mutableStateOf(false) }
    var showQualityDialog by rememberSaveable { mutableStateOf(false) }
    var showCookieDialog by rememberSaveable { mutableStateOf(false) }

    if (showCookieDialog) {
        TextFieldDialog(
            onDismiss = { showCookieDialog = false },
            icon = { Icon(painterResource(R.drawable.token), contentDescription = null) },
            title = { Text(stringResource(R.string.deezer_cookie_title)) },
            initialTextFieldValue = TextFieldValue(deezerCookie),
            placeholder = { Text(stringResource(R.string.deezer_cookie_placeholder)) },
            singleLine = false,
            isInputValid = { value ->
                value.isBlank() || isDeezerCookieConfigured(value)
            },
            onDone = { value ->
                deezerCookie = normalizeDeezerCookieInput(value).orEmpty()
                showCookieDialog = false
            },
            extraContent = {
                InfoLabel(text = stringResource(R.string.deezer_cookie_helper))
            },
        )
    }

    if (showResolverDialog) {
        TextFieldDialog(
            onDismiss = { showResolverDialog = false },
            icon = { Icon(painterResource(R.drawable.link), contentDescription = null) },
            title = { Text(stringResource(R.string.deezer_resolver_url)) },
            initialTextFieldValue = TextFieldValue(resolverUrl),
            placeholder = { Text(stringResource(R.string.deezer_resolver_url_placeholder)) },
            singleLine = true,
            isInputValid = { value ->
                value.isBlank() ||
                    value.startsWith("https://", ignoreCase = true) ||
                    value.startsWith("http://", ignoreCase = true)
            },
            onDone = { value ->
                resolverUrl = value.trim().ifBlank { DeezerAudioProvider.DEFAULT_RESOLVER_URL }
                showResolverDialog = false
            },
            extraContent = {
                InfoLabel(text = stringResource(R.string.deezer_resolver_url_helper))
            },
        )
    }

    if (showQualityDialog) {
        EnumDialog(
            onDismiss = { showQualityDialog = false },
            onSelect = { value ->
                audioQuality = value
                showQualityDialog = false
            },
            title = stringResource(R.string.deezer_audio_quality),
            current = audioQuality,
            values = DeezerAudioQualityOptions,
            valueText = { it.labelText() },
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
                        title = { Text(stringResource(R.string.deezer_web_login)) },
                        description = {
                            Text(
                                if (cookieConfigured) {
                                    stringResource(R.string.deezer_cookie_configured)
                                } else {
                                    stringResource(R.string.deezer_cookie_not_configured)
                                },
                            )
                        },
                        icon = painterResource(R.drawable.login),
                        onClick = {
                            navController.navigate("settings/integrations/deezer/login")
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.deezer_cookie_title)) },
                        description = { Text(stringResource(R.string.deezer_cookie_helper)) },
                        icon = painterResource(R.drawable.token),
                        onClick = {
                            showCookieDialog = true
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.prefer_deezer_audio)) },
                        description = { Text(stringResource(R.string.prefer_deezer_audio_desc)) },
                        trailingContent = {
                            Switch(
                                checked = preferDeezerAudio,
                                onCheckedChange = onPreferDeezerAudioChange,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (preferDeezerAudio) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        icon = painterResource(R.drawable.album),
                        onClick = {
                            onPreferDeezerAudioChange(!preferDeezerAudio)
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.deezer_resolver_url)) },
                        description = {
                            Text(stringResource(R.string.deezer_resolver_url_desc, resolverUrl))
                        },
                        icon = painterResource(R.drawable.link),
                        onClick = {
                            showResolverDialog = true
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.deezer_audio_quality)) },
                        description = { Text(audioQuality.labelText()) },
                        icon = painterResource(R.drawable.equalizer),
                        onClick = {
                            showQualityDialog = true
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.deezer_reset_resolver)) },
                        description = { Text(stringResource(R.string.deezer_resolver_url_placeholder)) },
                        icon = painterResource(R.drawable.delete),
                        enabled = resolverUrl != DeezerAudioProvider.DEFAULT_RESOLVER_URL,
                        onClick = {
                            resolverUrl = DeezerAudioProvider.DEFAULT_RESOLVER_URL
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.deezer_clear_cookie)) },
                        description = {
                            Text(
                                if (cookieConfigured) {
                                    stringResource(R.string.deezer_cookie_configured)
                                } else {
                                    stringResource(R.string.deezer_cookie_not_configured)
                                },
                            )
                        },
                        icon = painterResource(R.drawable.delete),
                        enabled = cookieConfigured,
                        onClick = {
                            deezerCookie = ""
                        },
                    ),
                ),
        )

        Spacer(Modifier.height(8.dp))
        InfoLabel(text = stringResource(R.string.deezer_web_login_desc))
        Spacer(Modifier.height(8.dp))
        InfoLabel(text = stringResource(R.string.deezer_integration_info))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.deezer_integration)) },
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

@Composable
private fun DeezerAudioQuality.labelText(): String =
    when (this) {
        DeezerAudioQuality.MP3_128 -> stringResource(R.string.deezer_quality_mp3_128)
        DeezerAudioQuality.MP3_320 -> stringResource(R.string.deezer_quality_mp3_320)
        DeezerAudioQuality.FLAC -> stringResource(R.string.deezer_quality_flac)
    }
