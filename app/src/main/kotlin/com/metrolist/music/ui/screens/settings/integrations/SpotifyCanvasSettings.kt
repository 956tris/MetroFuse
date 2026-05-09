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
import com.metrolist.music.constants.EmbedAnimatedCanvasKey
import com.metrolist.music.constants.SpotifyCanvasEnabledKey
import com.metrolist.music.constants.SpotifyCookieKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.InfoLabel
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.utils.spotify.isSpotifyCookieConfigured
import com.metrolist.music.utils.spotify.normalizeSpotifyCookieInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyCanvasSettings(
    navController: NavController,
) {
    val (spotifyCanvasEnabled, onSpotifyCanvasEnabledChange) =
        rememberPreference(SpotifyCanvasEnabledKey, false)
    val (embedAnimatedCanvas, onEmbedAnimatedCanvasChange) =
        rememberPreference(EmbedAnimatedCanvasKey, false)
    var spotifyCookie by rememberPreference(SpotifyCookieKey, "")
    val cookieConfigured = isSpotifyCookieConfigured(spotifyCookie)

    var showCookieDialog by rememberSaveable { mutableStateOf(false) }

    fun updateCanvasEnabled(enabled: Boolean) {
        if (enabled && !cookieConfigured) {
            navController.navigate("settings/integrations/spotify_canvas/login")
        } else {
            onSpotifyCanvasEnabledChange(enabled)
        }
    }

    if (showCookieDialog) {
        TextFieldDialog(
            onDismiss = { showCookieDialog = false },
            icon = { Icon(painterResource(R.drawable.token), contentDescription = null) },
            title = { Text(stringResource(R.string.spotify_canvas_cookie_title)) },
            initialTextFieldValue = TextFieldValue(spotifyCookie),
            placeholder = { Text(stringResource(R.string.spotify_canvas_cookie_placeholder)) },
            singleLine = false,
            isInputValid = { value ->
                value.isBlank() || normalizeSpotifyCookieInput(value) != null
            },
            onDone = { value ->
                val normalizedCookie = normalizeSpotifyCookieInput(value)
                spotifyCookie = normalizedCookie.orEmpty()
                onSpotifyCanvasEnabledChange(normalizedCookie != null)
                showCookieDialog = false
            },
            extraContent = {
                InfoLabel(text = stringResource(R.string.spotify_canvas_cookie_helper))
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
                        title = { Text(stringResource(R.string.enable_spotify_canvas)) },
                        description = {
                            Text(
                                if (cookieConfigured) {
                                    stringResource(R.string.enable_spotify_canvas_desc)
                                } else {
                                    stringResource(R.string.spotify_canvas_requires_cookie)
                                },
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = spotifyCanvasEnabled,
                                onCheckedChange = ::updateCanvasEnabled,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (spotifyCanvasEnabled) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        icon = painterResource(R.drawable.slow_motion_video),
                        onClick = {
                            updateCanvasEnabled(!spotifyCanvasEnabled)
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.embed_animated_canvas)) },
                        description = { Text(stringResource(R.string.embed_animated_canvas_desc)) },
                        trailingContent = {
                            Switch(
                                checked = embedAnimatedCanvas,
                                onCheckedChange = onEmbedAnimatedCanvasChange,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (embedAnimatedCanvas) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        icon = painterResource(R.drawable.slow_motion_video),
                        onClick = {
                            onEmbedAnimatedCanvasChange(!embedAnimatedCanvas)
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.spotify_canvas_web_login)) },
                        description = {
                            Text(
                                if (cookieConfigured) {
                                    stringResource(R.string.spotify_canvas_cookie_configured)
                                } else {
                                    stringResource(R.string.spotify_canvas_cookie_not_configured)
                                },
                            )
                        },
                        icon = painterResource(R.drawable.login),
                        onClick = {
                            navController.navigate("settings/integrations/spotify_canvas/login")
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.spotify_canvas_cookie_title)) },
                        description = { Text(stringResource(R.string.spotify_canvas_cookie_helper)) },
                        icon = painterResource(R.drawable.token),
                        onClick = { showCookieDialog = true },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.spotify_canvas_clear_cookie)) },
                        description = {
                            Text(
                                if (cookieConfigured) {
                                    stringResource(R.string.spotify_canvas_cookie_configured)
                                } else {
                                    stringResource(R.string.spotify_canvas_cookie_not_configured)
                                },
                            )
                        },
                        icon = painterResource(R.drawable.delete),
                        enabled = cookieConfigured,
                        onClick = {
                            spotifyCookie = ""
                            onSpotifyCanvasEnabledChange(false)
                        },
                    ),
                ),
        )

        Spacer(Modifier.height(8.dp))
        InfoLabel(text = stringResource(R.string.spotify_canvas_web_login_desc))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.spotify_canvas)) },
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
