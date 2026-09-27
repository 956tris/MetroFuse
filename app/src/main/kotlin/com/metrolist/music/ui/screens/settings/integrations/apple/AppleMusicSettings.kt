/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations.apple

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.metrolist.music.utils.rememberEnumPreference
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.apple.AppleAudioProvider
import com.metrolist.music.constants.AppleAudioQuality
import com.metrolist.music.constants.AppleAudioQualityKey
import com.metrolist.music.constants.AppleAudioQualityOptions
import com.metrolist.music.constants.AppleResolverEndpointsKey
import com.metrolist.music.ui.component.EnumDialog
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
    var appleAudioQuality by rememberEnumPreference(AppleAudioQualityKey, AppleAudioQuality.AAC)
    var resolverEndpoints by rememberPreference(AppleResolverEndpointsKey, "")
    val customResolverEndpointCount =
        remember(resolverEndpoints) {
            if (resolverEndpoints.isBlank()) 0
            else AppleAudioProvider.resolverEndpointBases(resolverEndpoints).size
        }
    var showQualityDialog by rememberSaveable { mutableStateOf(false) }
    var showResolverEndpointsDialog by rememberSaveable { mutableStateOf(false) }

    if (showResolverEndpointsDialog) {
        TextFieldDialog(
            onDismiss = { showResolverEndpointsDialog = false },
            icon = { Icon(painterResource(R.drawable.link), contentDescription = null) },
            title = { Text(stringResource(R.string.apple_music_resolver_endpoints)) },
            initialTextFieldValue = TextFieldValue(resolverEndpoints),
            placeholder = { Text(stringResource(R.string.apple_music_resolver_endpoints_placeholder)) },
            singleLine = false,
            isInputValid = { value ->
                AppleAudioProvider.isResolverEndpointsInputValid(value)
            },
            onDone = { value ->
                resolverEndpoints = AppleAudioProvider.normalizeResolverEndpointsInput(value)
                showResolverEndpointsDialog = false
            },
            extraContent = {
                InfoLabel(text = stringResource(R.string.apple_music_resolver_endpoints_helper))
            },
        )
    }

    if (showQualityDialog) {
        EnumDialog(
            onDismiss = { showQualityDialog = false },
            onSelect = {
                appleAudioQuality = it
                showQualityDialog = false
            },
            title = stringResource(R.string.apple_music_audio_quality),
            current = appleAudioQuality,
            values = AppleAudioQualityOptions,
            valueText = { quality ->
                when (quality) {
                    AppleAudioQuality.ATMOS -> stringResource(R.string.apple_music_quality_atmos)
                    AppleAudioQuality.AC3 -> stringResource(R.string.apple_music_quality_ac3)
                    AppleAudioQuality.AAC -> stringResource(R.string.apple_music_quality_aac)
                    AppleAudioQuality.AAC_WEB -> stringResource(R.string.apple_music_quality_aac_web)
                    AppleAudioQuality.AAC_BINAURAL -> stringResource(R.string.apple_music_quality_aac_binaural)
                    AppleAudioQuality.AAC_DOWNMIX -> stringResource(R.string.apple_music_quality_aac_downmix)
                    AppleAudioQuality.AAC_HE -> stringResource(R.string.apple_music_quality_aac_he)
                    AppleAudioQuality.AAC_HE_WEB -> stringResource(R.string.apple_music_quality_aac_he_web)
                    AppleAudioQuality.AAC_HE_BINAURAL -> stringResource(R.string.apple_music_quality_aac_he_binaural)
                    AppleAudioQuality.AAC_HE_DOWNMIX -> stringResource(R.string.apple_music_quality_aac_he_downmix)
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

        InfoLabel(text = stringResource(R.string.apple_music_subscription_dead))

        Spacer(Modifier.height(8.dp))

        Material3SettingsGroup(
            title = stringResource(R.string.general),
            items =
                listOf(
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.apple_music_audio_provider)) },
                        description = {
                            Text(stringResource(R.string.apple_music_audio_provider_desc))
                        },
                        icon = painterResource(R.drawable.music_note),
                        onClick = {
                            // Audio provider order is handled in a different screen, 
                            // but we can provide a shortcut or info here
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.apple_music_audio_quality)) },
                        description = {
                            Text(
                                when (appleAudioQuality) {
                                    AppleAudioQuality.ATMOS -> stringResource(R.string.apple_music_quality_atmos)
                                    AppleAudioQuality.AC3 -> stringResource(R.string.apple_music_quality_ac3)
                                    AppleAudioQuality.AAC -> stringResource(R.string.apple_music_quality_aac)
                                    AppleAudioQuality.AAC_WEB -> stringResource(R.string.apple_music_quality_aac_web)
                                    AppleAudioQuality.AAC_BINAURAL -> stringResource(R.string.apple_music_quality_aac_binaural)
                                    AppleAudioQuality.AAC_DOWNMIX -> stringResource(R.string.apple_music_quality_aac_downmix)
                                    AppleAudioQuality.AAC_HE -> stringResource(R.string.apple_music_quality_aac_he)
                                    AppleAudioQuality.AAC_HE_WEB -> stringResource(R.string.apple_music_quality_aac_he_web)
                                    AppleAudioQuality.AAC_HE_BINAURAL -> stringResource(R.string.apple_music_quality_aac_he_binaural)
                                    AppleAudioQuality.AAC_HE_DOWNMIX -> stringResource(R.string.apple_music_quality_aac_he_downmix)
                                }
                            )
                        },
                        icon = painterResource(R.drawable.settings),
                        onClick = { showQualityDialog = true },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.apple_music_resolver_endpoints)) },
                        description = {
                            Text(
                                if (customResolverEndpointCount > 0) {
                                    stringResource(
                                        R.string.apple_music_resolver_endpoints_desc_custom,
                                        customResolverEndpointCount,
                                    )
                                } else {
                                    stringResource(R.string.apple_music_resolver_endpoints_desc_default)
                                },
                            )
                        },
                        icon = painterResource(R.drawable.link),
                        onClick = {
                            showResolverEndpointsDialog = true
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.apple_music_reset_resolver)) },
                        description = { Text(stringResource(R.string.apple_music_resolver_endpoints_placeholder)) },
                        icon = painterResource(R.drawable.delete),
                        enabled = resolverEndpoints.isNotBlank(),
                        onClick = {
                            resolverEndpoints = ""
                        },
                    ),
                ),
        )

        Spacer(Modifier.height(8.dp))
        InfoLabel(text = stringResource(R.string.apple_music_integration_info))
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
