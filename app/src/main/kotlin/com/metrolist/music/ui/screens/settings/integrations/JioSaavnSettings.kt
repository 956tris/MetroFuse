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
import androidx.compose.runtime.remember
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
import com.metrolist.music.constants.JioSaavnAudioQuality
import com.metrolist.music.constants.JioSaavnAudioQualityKey
import com.metrolist.music.constants.JioSaavnAudioQualityOptions
import com.metrolist.music.constants.JioSaavnHomeLanguageKey
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.InfoLabel
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JioSaavnSettings(
    navController: NavController,
) {
    var audioQuality by rememberEnumPreference(JioSaavnAudioQualityKey, JioSaavnAudioQuality.HIGH)
    val (homeLanguage, onHomeLanguageChange) = rememberPreference(JioSaavnHomeLanguageKey, "hindi")
    var showQualityDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }

    val qualityHigh = stringResource(R.string.jiosaavn_quality_high)
    val qualityMedium = stringResource(R.string.jiosaavn_quality_medium)
    val qualityLow = stringResource(R.string.jiosaavn_quality_low)
    val qualityMini = stringResource(R.string.jiosaavn_quality_mini)
    val qualityUltraLow = stringResource(R.string.jiosaavn_quality_ultra_low)
    fun qualityLabel(quality: JioSaavnAudioQuality): String =
        when (quality) {
            JioSaavnAudioQuality.HIGH -> qualityHigh
            JioSaavnAudioQuality.MEDIUM -> qualityMedium
            JioSaavnAudioQuality.LOW -> qualityLow
            JioSaavnAudioQuality.MINI -> qualityMini
            JioSaavnAudioQuality.ULTRA_LOW -> qualityUltraLow
        }

    if (showQualityDialog) {
        EnumDialog(
            onDismiss = { showQualityDialog = false },
            onSelect = {
                audioQuality = it
                showQualityDialog = false
            },
            title = stringResource(R.string.jiosaavn_quality_title),
            current = audioQuality,
            values = JioSaavnAudioQualityOptions,
            valueText = { qualityLabel(it) },
        )
    }

    if (showLanguageDialog) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.jiosaavn_home_language_title)) },
            icon = { Icon(painterResource(R.drawable.language), null) },
            initialTextFieldValue = TextFieldValue(homeLanguage),
            isInputValid = { it.trim().matches(Regex("[A-Za-z]+")) },
            onDone = {
                onHomeLanguageChange(it.trim().lowercase(Locale.US))
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false },
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
                        title = { Text(stringResource(R.string.jiosaavn_quality_title)) },
                        description = { Text(qualityLabel(audioQuality)) },
                        icon = painterResource(R.drawable.settings),
                        onClick = { showQualityDialog = true },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.jiosaavn_home_language_title)) },
                        description = { Text(homeLanguage) },
                        icon = painterResource(R.drawable.language),
                        onClick = { showLanguageDialog = true },
                    ),
                ),
        )

        Spacer(Modifier.height(8.dp))
        InfoLabel(text = stringResource(R.string.jiosaavn_integration_desc))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.jiosaavn_integration)) },
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
