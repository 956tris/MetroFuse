/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.QobuzBackend
import com.metrolist.music.constants.QobuzBackendKey
import com.metrolist.music.constants.QobuzBackendOptions
import com.metrolist.music.constants.QobuzCountryKey
import com.metrolist.music.constants.QobuzCustomInstancesKey
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
fun QobuzSettings(
    navController: NavController
) {
    val (qobuzBackend, onQobuzBackendChange) = rememberEnumPreference(
        QobuzBackendKey,
        defaultValue = QobuzBackend.KENNY
    )
    val (qobuzCountry, onQobuzCountryChange) = rememberPreference(
        QobuzCountryKey,
        defaultValue = "US"
    )
    val (qobuzCustomInstances, onQobuzCustomInstancesChange) = rememberPreference(
        QobuzCustomInstancesKey,
        defaultValue = ""
    )

    var showBackendDialog by remember { mutableStateOf(false) }
    var showCountryDialog by remember { mutableStateOf(false) }
    var showInstancesDialog by remember { mutableStateOf(false) }

    if (showBackendDialog) {
        EnumDialog(
            onDismiss = { showBackendDialog = false },
            onSelect = {
                onQobuzBackendChange(it)
                showBackendDialog = false
            },
            title = stringResource(R.string.qobuz_backend),
            current = qobuzBackend,
            values = QobuzBackendOptions,
            valueText = {
                when (it) {
                    QobuzBackend.KENNY -> stringResource(R.string.qobuz_backend_kenny)
                }
            }
        )
    }

    if (showCountryDialog) {
        TextFieldDialog(
            title = { Text(stringResource(R.string.qobuz_country)) },
            icon = { Icon(painterResource(R.drawable.language), null) },
            initialTextFieldValue = TextFieldValue(qobuzCountry),
            isInputValid = { it.trim().matches(Regex("[A-Za-z]{2}")) },
            onDone = {
                onQobuzCountryChange(it.trim().uppercase(Locale.US))
                showCountryDialog = false
            },
            onDismiss = { showCountryDialog = false },
        )
    }

    if (showInstancesDialog) {
        TextFieldDialog(
            onDismiss = { showInstancesDialog = false },
            icon = { Icon(painterResource(R.drawable.link), contentDescription = null) },
            title = { Text(stringResource(R.string.qobuz_custom_instances)) },
            initialTextFieldValue = TextFieldValue(qobuzCustomInstances),
            placeholder = { Text(stringResource(R.string.qobuz_custom_instances_placeholder)) },
            singleLine = false,
            onDone = { value ->
                onQobuzCustomInstancesChange(value.trim())
                showInstancesDialog = false
            },
            extraContent = {
                InfoLabel(text = stringResource(R.string.qobuz_custom_instances_helper))
            },
        )
    }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top
                )
            )
        )

        Material3SettingsGroup(
            title = stringResource(R.string.general),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.settings),
                    title = { Text(stringResource(R.string.qobuz_backend)) },
                    description = {
                        Text(
                            when (qobuzBackend) {
                                QobuzBackend.KENNY -> stringResource(R.string.qobuz_backend_kenny)
                            }
                        )
                    },
                    onClick = { showBackendDialog = true }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.language),
                    title = { Text(stringResource(R.string.qobuz_country)) },
                    description = { Text(stringResource(R.string.qobuz_country_desc, qobuzCountry.uppercase(Locale.US))) },
                    onClick = { showCountryDialog = true }
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.link),
                    title = { Text(stringResource(R.string.qobuz_custom_instances)) },
                    description = {
                        val instances = qobuzCustomInstances.split("\n").filter { it.isNotBlank() }
                        Text(
                            if (instances.isEmpty()) {
                                stringResource(R.string.qobuz_custom_instances_desc_default)
                            } else {
                                stringResource(R.string.qobuz_custom_instances_desc_custom, instances.size)
                            }
                        )
                    },
                    onClick = { showInstancesDialog = true }
                )
            )
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.qobuz_integration)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null
                )
            }
        }
    )
}
