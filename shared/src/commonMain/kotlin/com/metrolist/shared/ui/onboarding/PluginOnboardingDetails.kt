package com.metrolist.shared.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.onboarding_plugins_beta_warning
import com.metrolist.shared.generated.resources.onboarding_plugins_compatibility
import com.metrolist.shared.generated.resources.onboarding_plugins_import
import com.metrolist.shared.generated.resources.onboarding_plugins_import_title
import com.metrolist.shared.generated.resources.onboarding_plugins_login
import com.metrolist.shared.generated.resources.onboarding_plugins_login_title
import com.metrolist.shared.generated.resources.onboarding_plugins_playback
import com.metrolist.shared.generated.resources.onboarding_plugins_playback_title
import com.metrolist.shared.ui.components.GroupedListItem
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.stringResource

@Composable
fun PluginOnboardingDetails(page: OnboardingPage) {
    val details =
        when (page) {
            OnboardingPage.WELCOME ->
                listOf(
                    Triple(
                        MetrolistIcons.Outlined.Download,
                        stringResource(Res.string.onboarding_plugins_import_title),
                        stringResource(Res.string.onboarding_plugins_import),
                    ),
                    Triple(
                        MetrolistIcons.Rounded.Lock,
                        stringResource(Res.string.onboarding_plugins_login_title),
                        stringResource(Res.string.onboarding_plugins_login),
                    ),
                    Triple(
                        MetrolistIcons.Outlined.Tune,
                        stringResource(Res.string.onboarding_plugins_playback_title),
                        stringResource(Res.string.onboarding_plugins_playback),
                    ),
                )

            OnboardingPage.FINISH ->
                listOf(
                    Triple(MetrolistIcons.Outlined.Info, null, stringResource(Res.string.onboarding_plugins_compatibility)),
                    Triple(
                        MetrolistIcons.Outlined.SettingsBackupRestore,
                        null,
                        stringResource(Res.string.onboarding_plugins_beta_warning),
                    ),
                )

            else -> emptyList()
        }
    if (details.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
            details.forEachIndexed { index, (icon, title, description) ->
                GroupedListItem(index = index, count = details.size) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = if (title == null) Alignment.CenterVertically else Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                            Text(description, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
