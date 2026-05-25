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
import com.metrolist.music.apple.AppleMusicWrapperManagerProvider
import com.metrolist.music.constants.AppleMusicFallbackEnabledKey
import com.metrolist.music.constants.AppleMusicForceAlacKey
import com.metrolist.music.constants.AppleMusicSuperFastKey
import com.metrolist.music.constants.AppleMusicWrapperHostKey
import com.metrolist.music.constants.AppleMusicWrapperSecureKey
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.InfoLabel
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppleMusicSettings(
    navController: NavController,
) {
    val (appleMusicFallbackEnabled, onAppleMusicFallbackEnabledChange) = rememberPreference(
        AppleMusicFallbackEnabledKey,
        defaultValue = true,
    )
    val (appleMusicForceAlac, onAppleMusicForceAlacChange) = rememberPreference(
        AppleMusicForceAlacKey,
        defaultValue = false,
    )
    val (appleMusicSuperFast, onAppleMusicSuperFastChange) = rememberPreference(
        AppleMusicSuperFastKey,
        defaultValue = false,
    )
    var appleWrapperHost by rememberPreference(
        AppleMusicWrapperHostKey,
        defaultValue = AppleMusicWrapperManagerProvider.DEFAULT_HOST,
    )
    val (appleWrapperSecure, onAppleWrapperSecureChange) = rememberPreference(
        AppleMusicWrapperSecureKey,
        defaultValue = true,
    )
    var showAppleWrapperHostDialog by rememberSaveable { mutableStateOf(false) }

    if (showAppleWrapperHostDialog) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.apple_music_wrapper_host)) },
            icon = { Icon(painterResource(R.drawable.link), contentDescription = null) },
            initialTextFieldValue = TextFieldValue(appleWrapperHost),
            placeholder = { Text(stringResource(R.string.apple_music_wrapper_host_placeholder)) },
            isInputValid = { value ->
                val normalized = AppleMusicWrapperManagerProvider.normalizeHost(value)
                normalized.isNotBlank() && !normalized.contains("/")
            },
            onDone = { value ->
                when {
                    value.trim().startsWith("http://", ignoreCase = true) -> onAppleWrapperSecureChange(false)
                    value.trim().startsWith("https://", ignoreCase = true) -> onAppleWrapperSecureChange(true)
                }
                appleWrapperHost = AppleMusicWrapperManagerProvider.normalizeHost(value)
                showAppleWrapperHostDialog = false
            },
            onDismiss = { showAppleWrapperHostDialog = false },
            extraContent = {
                InfoLabel(text = stringResource(R.string.apple_music_wrapper_host_helper))
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
                        icon = painterResource(R.drawable.library_music),
                        title = { Text(stringResource(R.string.apple_music_fallback)) },
                        description = { Text(stringResource(R.string.apple_music_fallback_desc)) },
                        trailingContent = {
                            Switch(
                                checked = appleMusicFallbackEnabled,
                                onCheckedChange = onAppleMusicFallbackEnabledChange,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (appleMusicFallbackEnabled) R.drawable.check else R.drawable.close,
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onAppleMusicFallbackEnabledChange(!appleMusicFallbackEnabled) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.graphic_eq),
                        title = { Text(stringResource(R.string.apple_music_force_alac)) },
                        description = { Text(stringResource(R.string.apple_music_force_alac_desc)) },
                        trailingContent = {
                            Switch(
                                checked = appleMusicForceAlac,
                                onCheckedChange = { enabled ->
                                    onAppleMusicForceAlacChange(enabled)
                                    if (enabled && !appleMusicFallbackEnabled) {
                                        onAppleMusicFallbackEnabledChange(true)
                                    }
                                },
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (appleMusicForceAlac) R.drawable.check else R.drawable.close,
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = {
                            val enabled = !appleMusicForceAlac
                            onAppleMusicForceAlacChange(enabled)
                            if (enabled && !appleMusicFallbackEnabled) {
                                onAppleMusicFallbackEnabledChange(true)
                            }
                        },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.speed),
                        title = { Text(stringResource(R.string.apple_music_super_fast)) },
                        description = { Text(stringResource(R.string.apple_music_super_fast_desc)) },
                        trailingContent = {
                            Switch(
                                checked = appleMusicSuperFast,
                                onCheckedChange = onAppleMusicSuperFastChange,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (appleMusicSuperFast) R.drawable.check else R.drawable.close,
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onAppleMusicSuperFastChange(!appleMusicSuperFast) },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(R.drawable.link),
                        title = { Text(stringResource(R.string.apple_music_wrapper_host)) },
                        description = {
                            Text(
                                stringResource(
                                    R.string.apple_music_wrapper_host_desc,
                                    AppleMusicWrapperManagerProvider.normalizeHost(appleWrapperHost),
                                ),
                            )
                        },
                        onClick = { showAppleWrapperHostDialog = true },
                    ),
                    Material3SettingsItem(
                        icon = painterResource(if (appleWrapperSecure) R.drawable.lock else R.drawable.lock_open),
                        title = { Text(stringResource(R.string.apple_music_wrapper_https)) },
                        description = { Text(stringResource(R.string.apple_music_wrapper_https_desc)) },
                        trailingContent = {
                            Switch(
                                checked = appleWrapperSecure,
                                onCheckedChange = onAppleWrapperSecureChange,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (appleWrapperSecure) R.drawable.check else R.drawable.close,
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                },
                            )
                        },
                        onClick = { onAppleWrapperSecureChange(!appleWrapperSecure) },
                    ),
                ),
        )

        Spacer(Modifier.height(8.dp))
        InfoLabel(text = stringResource(R.string.apple_music_integration_desc))
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
