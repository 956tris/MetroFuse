/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.apple.AppleMusicWrapperManagerProvider
import com.metrolist.music.constants.AppleMusicWrapperHostKey
import com.metrolist.music.constants.AppleMusicWrapperSecureKey
import com.metrolist.music.constants.DeezerResolverUrlKey
import com.metrolist.music.constants.QobuzBackend
import com.metrolist.music.constants.QobuzBackendKey
import com.metrolist.music.deezer.DeezerAudioProvider
import com.metrolist.music.providers.ProviderHealthChecker
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderHealthScreen(
    navController: NavController,
) {
    val deezerResolverUrl by rememberPreference(
        DeezerResolverUrlKey,
        DeezerAudioProvider.DEFAULT_RESOLVER_URL,
    )
    val qobuzBackend by rememberEnumPreference(QobuzBackendKey, QobuzBackend.JUMO)
    val appleWrapperHost by rememberPreference(
        AppleMusicWrapperHostKey,
        AppleMusicWrapperManagerProvider.DEFAULT_HOST,
    )
    val appleWrapperSecure by rememberPreference(AppleMusicWrapperSecureKey, true)
    val targets = remember(deezerResolverUrl, appleWrapperHost, appleWrapperSecure) {
        ProviderHealthChecker.targets(
            deezerResolverUrl = deezerResolverUrl,
            appleWrapperHost = appleWrapperHost,
            appleWrapperSecure = appleWrapperSecure,
        )
    }
    var refreshCounter by remember { mutableIntStateOf(0) }
    var results by remember { mutableStateOf<List<ProviderHealthChecker.Result>>(emptyList()) }
    var checking by remember { mutableStateOf(false) }
    var lastCheckedAt by remember { mutableStateOf<Long?>(null) }
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LaunchedEffect(targets, refreshCounter) {
        checking = true
        results = emptyList()
        results = ProviderHealthChecker.checkAll(targets)
        lastCheckedAt = System.currentTimeMillis()
        checking = false
    }

    val resultMap = results.associateBy { it.target.id }
    val selectedQobuzTarget = ProviderHealthChecker.qobuzTargetId(qobuzBackend.name)

    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top),
            ),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(R.string.provider_health_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                lastCheckedAt?.let { checkedAt ->
                    Text(
                        text = stringResource(
                            R.string.provider_health_last_checked,
                            timeFormatter.format(Date(checkedAt)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(
                enabled = !checking,
                onClick = { refreshCounter++ },
            ) {
                Icon(
                    painter = painterResource(R.drawable.refresh),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.provider_health_refresh))
            }
        }

        targets
            .groupBy { it.group }
            .forEach { (group, groupTargets) ->
                Spacer(modifier = Modifier.height(12.dp))
                Material3SettingsGroup(
                    title = group,
                    items = groupTargets.map { target ->
                        val result = resultMap[target.id]
                        val isSelected = target.id == selectedQobuzTarget
                        Material3SettingsItem(
                            icon = providerIcon(target.group),
                            title = {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(target.name)
                                    if (isSelected) {
                                        Text(
                                            text = stringResource(R.string.provider_health_selected),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            },
                            description = {
                                ProviderHealthDescription(
                                    target = target,
                                    result = result,
                                    checking = checking,
                                )
                            },
                            trailingContent = {
                                ProviderHealthStatus(
                                    result = result,
                                    checking = checking,
                                )
                            },
                        )
                    },
                )
            }

        Spacer(modifier = Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.provider_health)) },
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
private fun ProviderHealthDescription(
    target: ProviderHealthChecker.Target,
    result: ProviderHealthChecker.Result?,
    checking: Boolean,
) {
    Column {
        Text(target.detail)
        Text(
            text = target.endpoint.compactEndpoint(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.74f),
        )
        Text(
            text = when {
                result != null -> result.message
                checking -> stringResource(R.string.provider_health_checking)
                else -> stringResource(R.string.provider_health_not_checked)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.74f),
        )
    }
}

@Composable
private fun ProviderHealthStatus(
    result: ProviderHealthChecker.Result?,
    checking: Boolean,
) {
    if (result == null && checking) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.dp,
        )
        return
    }

    val status = result?.status
    Column(
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = when (status) {
                ProviderHealthChecker.Status.ONLINE -> stringResource(R.string.provider_health_online)
                ProviderHealthChecker.Status.REACHABLE -> stringResource(R.string.provider_health_reachable)
                ProviderHealthChecker.Status.OFFLINE -> stringResource(R.string.provider_health_offline)
                null -> stringResource(R.string.provider_health_not_checked)
            },
            style = MaterialTheme.typography.labelMedium,
            color = status.statusColor(),
        )
        result?.latencyMs?.let { latency ->
            Text(
                text = stringResource(R.string.provider_health_latency, latency),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun providerIcon(group: String): Painter =
    painterResource(
        when (group) {
            "SoundCloud" -> R.drawable.cloud
            "Qobuz" -> R.drawable.speed
            "YouTube Music" -> R.drawable.play
            else -> R.drawable.music_note
        },
    )

@Composable
private fun ProviderHealthChecker.Status?.statusColor(): Color =
    when (this) {
        ProviderHealthChecker.Status.ONLINE -> MaterialTheme.colorScheme.primary
        ProviderHealthChecker.Status.REACHABLE -> MaterialTheme.colorScheme.tertiary
        ProviderHealthChecker.Status.OFFLINE -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun String.compactEndpoint(): String =
    toHttpUrlOrNull()
        ?.let { url ->
            buildString {
                append(url.host)
                if (url.encodedPath.isNotBlank() && url.encodedPath != "/") {
                    append(url.encodedPath)
                }
            }
        }
        ?: this
