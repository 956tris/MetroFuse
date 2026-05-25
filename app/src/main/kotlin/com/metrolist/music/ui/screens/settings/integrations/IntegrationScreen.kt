/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.IntegrationCard
import com.metrolist.music.ui.component.IntegrationCardItem
import com.metrolist.music.ui.utils.backToMain

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntegrationScreen(
    navController: NavController
) {
    Column(
        Modifier
            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        IntegrationCard(
            title = stringResource(R.string.general),
            items = listOf(
                IntegrationCardItem(
                    icon = painterResource(R.drawable.discord),
                    title = { Text(stringResource(R.string.discord_integration)) },
                    onClick = {
                        navController.navigate("settings/integrations/discord")
                    }
                ),
                IntegrationCardItem(
                    icon = painterResource(R.drawable.music_note),
                    title = { Text(stringResource(R.string.lastfm_integration)) },
                    onClick = {
                        navController.navigate("settings/integrations/lastfm")
                    }
                ),
                IntegrationCardItem(
                    icon = painterResource(R.drawable.slow_motion_video),
                    title = { Text(stringResource(R.string.spotify_canvas)) },
                    onClick = {
                        navController.navigate("settings/integrations/spotify_canvas")
                    }
                ),
                IntegrationCardItem(
                    icon = painterResource(R.drawable.library_music),
                    title = { Text(stringResource(R.string.apple_music_integration)) },
                    description = { Text(stringResource(R.string.apple_music_integration_desc)) },
                    onClick = {
                        navController.navigate("settings/integrations/apple_music")
                    }
                ),
                IntegrationCardItem(
                    icon = painterResource(R.drawable.music_note),
                    title = { Text(stringResource(R.string.tidal_integration)) },
                    onClick = {
                        navController.navigate("settings/integrations/tidal")
                    }
                ),
                IntegrationCardItem(
                    icon = painterResource(R.drawable.music_note),
                    title = { Text(stringResource(R.string.deezer_integration)) },
                    description = { Text(stringResource(R.string.deezer_integration_desc)) },
                    onClick = {
                        navController.navigate("settings/integrations/deezer")
                    }
                ),
                IntegrationCardItem(
                    icon = painterResource(R.drawable.cloud),
                    title = { Text(stringResource(R.string.soundcloud_integration)) },
                    description = { Text(stringResource(R.string.soundcloud_web_login_desc)) },
                    onClick = {
                        navController.navigate("settings/integrations/soundcloud")
                    }
                ),
                IntegrationCardItem(
                    icon = painterResource(R.drawable.music_note),
                    title = { Text(stringResource(R.string.instagram_integration)) },
                    description = { Text(stringResource(R.string.instagram_web_login_desc)) },
                    onClick = {
                        navController.navigate("settings/integrations/instagram")
                    }
                )
            )
        )
    }

    TopAppBar(
        title = { Text(stringResource(R.string.integrations)) },
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
        }
    )
}
