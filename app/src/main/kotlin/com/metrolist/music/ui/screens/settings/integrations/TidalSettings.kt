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
import com.metrolist.music.constants.TidalArtworkFallbackEnabledKey
import com.metrolist.music.constants.TidalCookieKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.InfoLabel
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.tidal.isTidalCookieConfigured
import com.metrolist.music.utils.tidal.normalizeTidalCookieInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TidalSettings(
    navController: NavController,
) {
    val (tidalArtworkFallbackEnabled, onTidalArtworkFallbackEnabledChange) =
        rememberPreference(TidalArtworkFallbackEnabledKey, false)
    var tidalCookie by rememberPreference(TidalCookieKey, "")
    val cookieConfigured = isTidalCookieConfigured(tidalCookie)
    var showCookieDialog by rememberSaveable { mutableStateOf(false) }

    if (showCookieDialog) {
        TextFieldDialog(
            onDismiss = { showCookieDialog = false },
            icon = { Icon(painterResource(R.drawable.token), contentDescription = null) },
            title = { Text(stringResource(R.string.tidal_cookie_title)) },
            initialTextFieldValue = TextFieldValue(tidalCookie),
            placeholder = { Text(stringResource(R.string.tidal_cookie_placeholder)) },
            singleLine = false,
            isInputValid = { value ->
                value.isBlank() || isTidalCookieConfigured(value)
            },
            onDone = { value ->
                tidalCookie = normalizeTidalCookieInput(value).orEmpty()
                showCookieDialog = false
            },
            extraContent = {
                InfoLabel(text = stringResource(R.string.tidal_cookie_helper))
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
                        title = { Text(stringResource(R.string.tidal_artwork_fallback)) },
                        description = { Text(stringResource(R.string.tidal_artwork_fallback_desc)) },
                        trailingContent = {
                            Switch(
                                checked = tidalArtworkFallbackEnabled,
                                onCheckedChange = onTidalArtworkFallbackEnabledChange,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (tidalArtworkFallbackEnabled) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        icon = painterResource(R.drawable.album),
                        onClick = {
                            onTidalArtworkFallbackEnabledChange(!tidalArtworkFallbackEnabled)
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.tidal_web_login)) },
                        description = {
                            Text(
                                if (cookieConfigured) {
                                    stringResource(R.string.tidal_cookie_configured)
                                } else {
                                    stringResource(R.string.tidal_cookie_not_configured)
                                },
                            )
                        },
                        icon = painterResource(R.drawable.login),
                        onClick = {
                            navController.navigate("settings/integrations/tidal/login")
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.tidal_cookie_title)) },
                        description = { Text(stringResource(R.string.tidal_cookie_helper)) },
                        icon = painterResource(R.drawable.token),
                        onClick = {
                            showCookieDialog = true
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.tidal_clear_cookie)) },
                        description = {
                            Text(
                                if (cookieConfigured) {
                                    stringResource(R.string.tidal_cookie_configured)
                                } else {
                                    stringResource(R.string.tidal_cookie_not_configured)
                                },
                            )
                        },
                        icon = painterResource(R.drawable.delete),
                        enabled = cookieConfigured,
                        onClick = {
                            tidalCookie = ""
                        },
                    ),
                ),
        )

        Spacer(Modifier.height(8.dp))
        InfoLabel(text = stringResource(R.string.tidal_web_login_desc))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.tidal_integration)) },
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
