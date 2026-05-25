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
import com.metrolist.music.constants.InstagramAppIdKey
import com.metrolist.music.constants.InstagramCookieKey
import com.metrolist.music.constants.InstagramUserAgentKey
import com.metrolist.music.constants.InstagramUuidKey
import com.metrolist.music.instagram.InstagramAudioProvider
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.InfoLabel
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.instagram.isInstagramCookieConfigured
import com.metrolist.music.utils.instagram.normalizeInstagramCookieInput
import com.metrolist.music.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstagramSettings(
    navController: NavController,
) {
    var instagramCookie by rememberPreference(InstagramCookieKey, "")
    var instagramUserAgent by rememberPreference(InstagramUserAgentKey, InstagramAudioProvider.DEFAULT_USER_AGENT)
    var instagramAppId by rememberPreference(InstagramAppIdKey, InstagramAudioProvider.DEFAULT_APP_ID)
    var instagramUuid by rememberPreference(InstagramUuidKey, "")
    val cookieConfigured = isInstagramCookieConfigured(instagramCookie)
    var showCookieDialog by rememberSaveable { mutableStateOf(false) }
    var showUserAgentDialog by rememberSaveable { mutableStateOf(false) }
    var showAppIdDialog by rememberSaveable { mutableStateOf(false) }
    var showUuidDialog by rememberSaveable { mutableStateOf(false) }

    if (showCookieDialog) {
        TextFieldDialog(
            onDismiss = { showCookieDialog = false },
            icon = { Icon(painterResource(R.drawable.token), contentDescription = null) },
            title = { Text(stringResource(R.string.instagram_cookie_title)) },
            initialTextFieldValue = TextFieldValue(instagramCookie),
            placeholder = { Text(stringResource(R.string.instagram_cookie_placeholder)) },
            singleLine = false,
            isInputValid = { value ->
                value.isBlank() || isInstagramCookieConfigured(value)
            },
            onDone = { value ->
                instagramCookie = normalizeInstagramCookieInput(value).orEmpty()
                showCookieDialog = false
            },
            extraContent = {
                InfoLabel(text = stringResource(R.string.instagram_cookie_helper))
            },
        )
    }

    if (showUserAgentDialog) {
        TextFieldDialog(
            onDismiss = { showUserAgentDialog = false },
            icon = { Icon(painterResource(R.drawable.language), contentDescription = null) },
            title = { Text(stringResource(R.string.instagram_user_agent_title)) },
            initialTextFieldValue =
                TextFieldValue(instagramUserAgent.ifBlank { InstagramAudioProvider.DEFAULT_USER_AGENT }),
            placeholder = { Text(InstagramAudioProvider.DEFAULT_USER_AGENT) },
            singleLine = false,
            isInputValid = { it.isNotBlank() },
            onDone = { value ->
                instagramUserAgent = value.trim().ifBlank { InstagramAudioProvider.DEFAULT_USER_AGENT }
                showUserAgentDialog = false
            },
            extraContent = {
                InfoLabel(text = stringResource(R.string.instagram_user_agent_desc))
            },
        )
    }

    if (showAppIdDialog) {
        TextFieldDialog(
            onDismiss = { showAppIdDialog = false },
            icon = { Icon(painterResource(R.drawable.settings), contentDescription = null) },
            title = { Text(stringResource(R.string.instagram_app_id_title)) },
            initialTextFieldValue =
                TextFieldValue(instagramAppId.ifBlank { InstagramAudioProvider.DEFAULT_APP_ID }),
            placeholder = { Text(InstagramAudioProvider.DEFAULT_APP_ID) },
            singleLine = true,
            isInputValid = { it.isBlank() || it.all { char -> char.isDigit() } },
            onDone = { value ->
                instagramAppId = value.trim().ifBlank { InstagramAudioProvider.DEFAULT_APP_ID }
                showAppIdDialog = false
            },
            extraContent = {
                InfoLabel(text = stringResource(R.string.instagram_app_id_desc))
            },
        )
    }

    if (showUuidDialog) {
        TextFieldDialog(
            onDismiss = { showUuidDialog = false },
            icon = { Icon(painterResource(R.drawable.settings), contentDescription = null) },
            title = { Text(stringResource(R.string.instagram_uuid_title)) },
            initialTextFieldValue = TextFieldValue(instagramUuid),
            placeholder = { Text(stringResource(R.string.instagram_uuid_placeholder)) },
            singleLine = true,
            isInputValid = { it.isBlank() || it.length >= 8 },
            onDone = { value ->
                instagramUuid = value.trim()
                showUuidDialog = false
            },
            extraContent = {
                InfoLabel(text = stringResource(R.string.instagram_uuid_desc))
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
                        title = { Text(stringResource(R.string.instagram_web_login)) },
                        description = {
                            Text(
                                if (cookieConfigured) {
                                    stringResource(R.string.instagram_cookie_configured)
                                } else {
                                    stringResource(R.string.instagram_cookie_not_configured)
                                },
                            )
                        },
                        icon = painterResource(R.drawable.login),
                        onClick = {
                            navController.navigate("settings/integrations/instagram/login")
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.instagram_cookie_title)) },
                        description = { Text(stringResource(R.string.instagram_cookie_helper)) },
                        icon = painterResource(R.drawable.token),
                        onClick = { showCookieDialog = true },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.instagram_user_agent_title)) },
                        description = {
                            Text(
                                stringResource(
                                    R.string.instagram_setting_value,
                                    instagramUserAgent.ifBlank { InstagramAudioProvider.DEFAULT_USER_AGENT },
                                ),
                            )
                        },
                        icon = painterResource(R.drawable.language),
                        onClick = { showUserAgentDialog = true },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.instagram_app_id_title)) },
                        description = {
                            Text(
                                stringResource(
                                    R.string.instagram_setting_value,
                                    instagramAppId.ifBlank { InstagramAudioProvider.DEFAULT_APP_ID },
                                ),
                            )
                        },
                        icon = painterResource(R.drawable.settings),
                        onClick = { showAppIdDialog = true },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.instagram_uuid_title)) },
                        description = {
                            val uuidValue = instagramUuid.ifBlank { stringResource(R.string.instagram_not_set) }
                            Text(
                                stringResource(
                                    R.string.instagram_setting_value,
                                    uuidValue,
                                ),
                            )
                        },
                        icon = painterResource(R.drawable.settings),
                        onClick = { showUuidDialog = true },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.instagram_clear_cookie)) },
                        description = {
                            Text(
                                if (cookieConfigured) {
                                    stringResource(R.string.instagram_cookie_configured)
                                } else {
                                    stringResource(R.string.instagram_cookie_not_configured)
                                },
                            )
                        },
                        icon = painterResource(R.drawable.delete),
                        enabled = cookieConfigured,
                        onClick = {
                            instagramCookie = ""
                        },
                    ),
                ),
        )

        Spacer(Modifier.height(8.dp))
        InfoLabel(text = stringResource(R.string.instagram_web_login_desc))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.instagram_integration)) },
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
