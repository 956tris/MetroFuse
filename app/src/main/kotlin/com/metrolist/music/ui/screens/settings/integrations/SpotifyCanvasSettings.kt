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
import com.metrolist.music.constants.CanvasArtworkPriority
import com.metrolist.music.constants.CanvasArtworkPriorityKey
import com.metrolist.music.constants.DownloadCanvasMode
import com.metrolist.music.constants.DownloadCanvasModeKey
import com.metrolist.music.constants.SpotifyCanvasEnabledKey
import com.metrolist.music.constants.SpotifyCookieKey
import com.metrolist.music.constants.SpotifyListeningHistoryEnabledKey
import com.metrolist.music.constants.SpotifyListeningHistoryGlobalKey
import com.metrolist.music.constants.SpotifySyncLikesKey
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.InfoLabel
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberEnumPreference
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
    val (spotifyListeningHistoryEnabled, onSpotifyListeningHistoryEnabledChange) =
        rememberPreference(SpotifyListeningHistoryEnabledKey, false)
    val (spotifyListeningHistoryGlobal, onSpotifyListeningHistoryGlobalChange) =
        rememberPreference(SpotifyListeningHistoryGlobalKey, false)
    val (spotifySyncLikes, onSpotifySyncLikesChange) =
        rememberPreference(SpotifySyncLikesKey, false)
    var canvasArtworkPriority by rememberEnumPreference(CanvasArtworkPriorityKey, CanvasArtworkPriority.APPLE_MUSIC)
    var downloadCanvasMode by rememberEnumPreference(DownloadCanvasModeKey, DownloadCanvasMode.OFF)
    var spotifyCookie by rememberPreference(SpotifyCookieKey, "")
    val cookieConfigured = isSpotifyCookieConfigured(spotifyCookie)

    var showCookieDialog by rememberSaveable { mutableStateOf(false) }
    var showPriorityDialog by rememberSaveable { mutableStateOf(false) }
    var showDownloadCanvasDialog by rememberSaveable { mutableStateOf(false) }

    fun updateCanvasEnabled(enabled: Boolean) {
        if (enabled && !cookieConfigured) {
            navController.navigate("settings/integrations/spotify_canvas/login")
        } else {
            onSpotifyCanvasEnabledChange(enabled)
        }
    }

    fun updateListeningHistoryEnabled(enabled: Boolean) {
        if (enabled && !cookieConfigured) {
            navController.navigate("settings/integrations/spotify_canvas/login")
        } else {
            onSpotifyListeningHistoryEnabledChange(enabled)
            if (!enabled) {
                onSpotifyListeningHistoryGlobalChange(false)
            }
        }
    }

    fun updateListeningHistoryGlobal(enabled: Boolean) {
        if (enabled && !cookieConfigured) {
            navController.navigate("settings/integrations/spotify_canvas/login")
        } else if (enabled && !spotifyListeningHistoryEnabled) {
            updateListeningHistoryEnabled(true)
            onSpotifyListeningHistoryGlobalChange(true)
        } else {
            onSpotifyListeningHistoryGlobalChange(enabled)
        }
    }

    fun updateSyncLikes(enabled: Boolean) {
        if (enabled && !cookieConfigured) {
            navController.navigate("settings/integrations/spotify_canvas/login")
        } else {
            onSpotifySyncLikesChange(enabled)
        }
    }

    fun updateDownloadCanvasMode(mode: DownloadCanvasMode) {
        downloadCanvasMode = mode
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

    if (showPriorityDialog) {
        EnumDialog(
            onDismiss = { showPriorityDialog = false },
            onSelect = { value ->
                canvasArtworkPriority = value
                showPriorityDialog = false
            },
            title = stringResource(R.string.canvas_artwork_priority),
            current = canvasArtworkPriority,
            values = CanvasArtworkPriority.entries,
            valueText = { it.labelText() },
            valueDescription = { it.descriptionText() },
        )
    }

    if (showDownloadCanvasDialog) {
        EnumDialog(
            onDismiss = { showDownloadCanvasDialog = false },
            onSelect = { value ->
                updateDownloadCanvasMode(value)
                showDownloadCanvasDialog = false
            },
            title = stringResource(R.string.embed_animated_canvas),
            current = downloadCanvasMode,
            values = DownloadCanvasMode.entries,
            valueText = { it.labelText() },
            valueDescription = { it.descriptionText() },
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
                        title = { Text(stringResource(R.string.canvas_artwork_priority)) },
                        description = { Text(canvasArtworkPriority.descriptionText()) },
                        icon = painterResource(R.drawable.slow_motion_video),
                        onClick = {
                            showPriorityDialog = true
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.embed_animated_canvas)) },
                        description = { Text(downloadCanvasMode.descriptionText()) },
                        trailingContent = {
                            Icon(
                                painter = painterResource(R.drawable.expand_more),
                                contentDescription = null,
                            )
                        },
                        icon = painterResource(R.drawable.slow_motion_video),
                        onClick = {
                            showDownloadCanvasDialog = true
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.spotify_listening_history)) },
                        description = {
                            Text(
                                if (cookieConfigured) {
                                    stringResource(R.string.spotify_listening_history_desc)
                                } else {
                                    stringResource(R.string.spotify_canvas_requires_cookie)
                                },
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = spotifyListeningHistoryEnabled,
                                onCheckedChange = ::updateListeningHistoryEnabled,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (spotifyListeningHistoryEnabled) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        icon = painterResource(R.drawable.history),
                        onClick = {
                            updateListeningHistoryEnabled(!spotifyListeningHistoryEnabled)
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.spotify_listening_history_global)) },
                        description = {
                            Text(
                                if (cookieConfigured) {
                                    stringResource(R.string.spotify_listening_history_global_desc)
                                } else {
                                    stringResource(R.string.spotify_canvas_requires_cookie)
                                },
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = spotifyListeningHistoryGlobal,
                                enabled = spotifyListeningHistoryEnabled && cookieConfigured,
                                onCheckedChange = ::updateListeningHistoryGlobal,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (spotifyListeningHistoryGlobal) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        icon = painterResource(R.drawable.sync),
                        enabled = spotifyListeningHistoryEnabled && cookieConfigured,
                        onClick = {
                            updateListeningHistoryGlobal(!spotifyListeningHistoryGlobal)
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.spotify_sync_likes)) },
                        description = {
                            Text(
                                if (cookieConfigured) {
                                    stringResource(R.string.spotify_sync_likes_description)
                                } else {
                                    stringResource(R.string.spotify_canvas_requires_cookie)
                                },
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = spotifySyncLikes,
                                onCheckedChange = ::updateSyncLikes,
                                thumbContent = {
                                    Icon(
                                        painter =
                                            painterResource(
                                                id = if (spotifySyncLikes) R.drawable.check else R.drawable.close,
                                            ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        icon = painterResource(R.drawable.favorite),
                        onClick = {
                            updateSyncLikes(!spotifySyncLikes)
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
                            onSpotifyListeningHistoryEnabledChange(false)
                            onSpotifyListeningHistoryGlobalChange(false)
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

@Composable
private fun CanvasArtworkPriority.labelText(): String =
    when (this) {
        CanvasArtworkPriority.APPLE_MUSIC -> stringResource(R.string.canvas_artwork_priority_apple)
        CanvasArtworkPriority.SPOTIFY -> stringResource(R.string.canvas_artwork_priority_spotify)
    }

@Composable
private fun CanvasArtworkPriority.descriptionText(): String =
    when (this) {
        CanvasArtworkPriority.APPLE_MUSIC -> stringResource(R.string.canvas_artwork_priority_apple_desc)
        CanvasArtworkPriority.SPOTIFY -> stringResource(R.string.canvas_artwork_priority_spotify_desc)
    }

@Composable
private fun DownloadCanvasMode.labelText(): String =
    when (this) {
        DownloadCanvasMode.OFF -> stringResource(R.string.download_canvas_mode_off)
        DownloadCanvasMode.SPOTIFY -> stringResource(R.string.download_canvas_mode_spotify)
        DownloadCanvasMode.APPLE_MUSIC -> stringResource(R.string.download_canvas_mode_apple)
        DownloadCanvasMode.BOTH -> stringResource(R.string.download_canvas_mode_both)
    }

@Composable
private fun DownloadCanvasMode.descriptionText(): String =
    when (this) {
        DownloadCanvasMode.OFF -> stringResource(R.string.download_canvas_mode_off_desc)
        DownloadCanvasMode.SPOTIFY -> stringResource(R.string.download_canvas_mode_spotify_desc)
        DownloadCanvasMode.APPLE_MUSIC -> stringResource(R.string.download_canvas_mode_apple_desc)
        DownloadCanvasMode.BOTH -> stringResource(R.string.download_canvas_mode_both_desc)
    }
