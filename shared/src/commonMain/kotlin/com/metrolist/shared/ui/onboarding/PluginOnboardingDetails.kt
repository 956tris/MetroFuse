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
import com.metrolist.shared.generated.resources.onboarding_plugins_interfaces
import com.metrolist.shared.generated.resources.onboarding_plugins_providers
import com.metrolist.shared.ui.components.GroupedListItem
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.stringResource

@Composable
fun PluginOnboardingDetails(page: OnboardingPage) {
    val details =
        when (page) {
            OnboardingPage.WELCOME ->
                listOf(
                    MetrolistIcons.Outlined.MusicNote to stringResource(Res.string.onboarding_plugins_providers),
                    MetrolistIcons.Outlined.Tune to stringResource(Res.string.onboarding_plugins_interfaces),
                    MetrolistIcons.Outlined.Download to stringResource(Res.string.onboarding_plugins_import),
                )

            OnboardingPage.FINISH ->
                listOf(
                    MetrolistIcons.Outlined.Info to stringResource(Res.string.onboarding_plugins_compatibility),
                    MetrolistIcons.Outlined.SettingsBackupRestore to stringResource(Res.string.onboarding_plugins_beta_warning),
                )

            else -> emptyList()
        }
    if (details.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(top = 24.dp)) {
            details.forEachIndexed { index, (icon, label) ->
                GroupedListItem(index = index, count = details.size) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
