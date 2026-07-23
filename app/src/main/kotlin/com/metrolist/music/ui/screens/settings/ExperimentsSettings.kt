/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings

import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.R
import com.metrolist.music.constants.ExperimentalAppleMusicCoverFadeKey
import com.metrolist.music.constants.ExperimentalAppleMusicLyricsKey
import com.metrolist.music.constants.ExperimentalAppleMusicLyricsSizeKey
import com.metrolist.music.constants.ExperimentalLiveWallpaperKey
import com.metrolist.music.constants.ExperimentalGalaxyBlurAdaptiveArtworkKey
import com.metrolist.music.constants.ExperimentalGalaxyBlurMirroredColorsKey
import com.metrolist.music.constants.ExperimentalSmoothInlineLyricsKey
import com.metrolist.music.ui.component.DefaultDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.rememberPreference
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperimentsSettings(
    navController: NavController,
) {
    val (appleMusicCoverFade, onAppleMusicCoverFadeChange) = rememberPreference(
        key = ExperimentalAppleMusicCoverFadeKey,
        defaultValue = false,
    )
    val (galaxyBlurAdaptiveArtwork, onGalaxyBlurAdaptiveArtworkChange) = rememberPreference(
        key = ExperimentalGalaxyBlurAdaptiveArtworkKey,
        defaultValue = false,
    )
    val (galaxyBlurMirroredColors, onGalaxyBlurMirroredColorsChange) = rememberPreference(
        key = ExperimentalGalaxyBlurMirroredColorsKey,
        defaultValue = false,
    )
    val (smoothInlineLyrics, onSmoothInlineLyricsChange) = rememberPreference(
        key = ExperimentalSmoothInlineLyricsKey,
        defaultValue = false,
    )
    val (appleMusicLyrics, onAppleMusicLyricsChange) = rememberPreference(
        key = ExperimentalAppleMusicLyricsKey,
        defaultValue = false,
    )
    val (appleMusicLyricsSize, onAppleMusicLyricsSizeChange) = rememberPreference(
        key = ExperimentalAppleMusicLyricsSizeKey,
        defaultValue = 46f,
    )
    val (liveWallpaper, onLiveWallpaperChange) = rememberPreference(
        key = ExperimentalLiveWallpaperKey,
        defaultValue = false,
    )
    var showAppleMusicLyricsSizeDialog by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
                ),
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Top,
                ),
            ),
        )

        Material3SettingsGroup(
            title = stringResource(R.string.experiments_player),
            items = listOf(
                Material3SettingsItem(
                    icon = painterResource(R.drawable.gradient),
                    title = { Text(stringResource(R.string.experimental_apple_music_cover_fade)) },
                    description = { Text(stringResource(R.string.experimental_apple_music_cover_fade_desc)) },
                    trailingContent = {
                        Switch(
                            checked = appleMusicCoverFade,
                            onCheckedChange = onAppleMusicCoverFadeChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (appleMusicCoverFade) R.drawable.check else R.drawable.close,
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            },
                        )
                    },
                    onClick = { onAppleMusicCoverFadeChange(!appleMusicCoverFade) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.gradient),
                    title = { Text(stringResource(R.string.experimental_galaxy_blur_adaptive_artwork)) },
                    description = { Text(stringResource(R.string.experimental_galaxy_blur_adaptive_artwork_desc)) },
                    trailingContent = {
                        Switch(
                            checked = galaxyBlurAdaptiveArtwork,
                            onCheckedChange = onGalaxyBlurAdaptiveArtworkChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (galaxyBlurAdaptiveArtwork) R.drawable.check else R.drawable.close,
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            },
                        )
                    },
                    onClick = { onGalaxyBlurAdaptiveArtworkChange(!galaxyBlurAdaptiveArtwork) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.gradient),
                    title = { Text(stringResource(R.string.experimental_galaxy_blur_mirrored_colors)) },
                    description = { Text(stringResource(R.string.experimental_galaxy_blur_mirrored_colors_desc)) },
                    trailingContent = {
                        Switch(
                            checked = galaxyBlurMirroredColors,
                            onCheckedChange = onGalaxyBlurMirroredColorsChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (galaxyBlurMirroredColors) R.drawable.check else R.drawable.close,
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            },
                        )
                    },
                    onClick = { onGalaxyBlurMirroredColorsChange(!galaxyBlurMirroredColors) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.lyrics),
                    title = { Text(stringResource(R.string.experimental_smooth_inline_lyrics)) },
                    description = { Text(stringResource(R.string.experimental_smooth_inline_lyrics_desc)) },
                    trailingContent = {
                        Switch(
                            checked = smoothInlineLyrics,
                            onCheckedChange = onSmoothInlineLyricsChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (smoothInlineLyrics) R.drawable.check else R.drawable.close,
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            },
                        )
                    },
                    onClick = { onSmoothInlineLyricsChange(!smoothInlineLyrics) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.lyrics),
                    title = { Text(stringResource(R.string.experimental_apple_music_lyrics)) },
                    description = { Text(stringResource(R.string.experimental_apple_music_lyrics_desc)) },
                    trailingContent = {
                        Switch(
                            checked = appleMusicLyrics,
                            onCheckedChange = onAppleMusicLyricsChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (appleMusicLyrics) R.drawable.check else R.drawable.close,
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            },
                        )
                    },
                    onClick = { onAppleMusicLyricsChange(!appleMusicLyrics) },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.lyrics),
                    title = { Text(stringResource(R.string.experimental_apple_music_lyrics_size)) },
                    description = {
                        Text(
                            stringResource(
                                R.string.experimental_apple_music_lyrics_size_value,
                                appleMusicLyricsSize.roundToInt(),
                            ),
                        )
                    },
                    enabled = appleMusicLyrics,
                    onClick = { showAppleMusicLyricsSizeDialog = true },
                ),
                Material3SettingsItem(
                    icon = painterResource(R.drawable.slow_motion_video),
                    title = { Text(stringResource(R.string.live_wallpaper)) },
                    description = { Text(stringResource(R.string.live_wallpaper_desc)) },
                    trailingContent = {
                        Switch(
                            checked = liveWallpaper,
                            onCheckedChange = onLiveWallpaperChange,
                            thumbContent = {
                                Icon(
                                    painter = painterResource(
                                        id = if (liveWallpaper) R.drawable.check else R.drawable.close,
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize),
                                )
                            },
                        )
                    },
                    onClick = { onLiveWallpaperChange(!liveWallpaper) },
                ),
            ),
        )

        Spacer(modifier = Modifier.height(16.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.experiments)) },
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

    if (showAppleMusicLyricsSizeDialog) {
        var tempTextSize by remember { mutableFloatStateOf(appleMusicLyricsSize) }

        DefaultDialog(
            onDismiss = {
                tempTextSize = appleMusicLyricsSize
                showAppleMusicLyricsSizeDialog = false
            },
            buttons = {
                TextButton(
                    onClick = {
                        tempTextSize = 46f
                    },
                ) {
                    Text(stringResource(R.string.reset))
                }

                Spacer(modifier = Modifier.weight(1f))

                TextButton(
                    onClick = {
                        tempTextSize = appleMusicLyricsSize
                        showAppleMusicLyricsSizeDialog = false
                    },
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
                TextButton(
                    onClick = {
                        onAppleMusicLyricsSizeChange(tempTextSize)
                        showAppleMusicLyricsSizeDialog = false
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.experimental_apple_music_lyrics_size),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Text(
                    text = stringResource(
                        R.string.experimental_apple_music_lyrics_size_value,
                        tempTextSize.roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Slider(
                    value = tempTextSize,
                    onValueChange = { tempTextSize = it },
                    valueRange = 34f..58f,
                    steps = 23,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
