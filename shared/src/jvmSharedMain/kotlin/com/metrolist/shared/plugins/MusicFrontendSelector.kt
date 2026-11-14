package com.metrolist.shared.plugins

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.home
import com.metrolist.shared.generated.resources.music_frontend_offline
import com.metrolist.shared.generated.resources.music_frontend_switch
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.stringResource

@Composable
fun MusicFrontendSelector() {
    val frontends by PluginServicesStore.frontends.collectAsState()
    val owner = effectiveMusicFrontendOwner()
    val selectedTitle = frontends.firstOrNull { it.owner == owner }?.title ?: stringResource(Res.string.music_frontend_offline)
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(stringResource(Res.string.home), style = MaterialTheme.typography.titleLarge)
                    Text(selectedTitle, style = MaterialTheme.typography.labelSmall)
                }
                Icon(MetrolistIcons.Outlined.KeyboardArrowDown, stringResource(Res.string.music_frontend_switch))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.music_frontend_offline)) },
                onClick = {
                    selectMusicFrontend("")
                    expanded = false
                },
            )
            frontends.forEach { frontend ->
                DropdownMenuItem(
                    text = { Text(frontend.title) },
                    onClick = {
                        selectMusicFrontend(frontend.owner)
                        expanded = false
                    },
                )
            }
        }
    }
}
