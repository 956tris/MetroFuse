/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.settings.integrations

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.media3.common.Player.STATE_READY
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.constants.DiscordAccessTokenKey
import com.metrolist.music.constants.DiscordActivityNameKey
import com.metrolist.music.constants.DiscordActivityTypeKey
import com.metrolist.music.constants.DiscordAdvancedModeKey
import com.metrolist.music.constants.DiscordAnimatedCanvasQuality
import com.metrolist.music.constants.DiscordAnimatedCanvasQualityKey
import com.metrolist.music.constants.DiscordAnimatedCanvasKey
import com.metrolist.music.constants.DiscordAvatarKey
import com.metrolist.music.constants.DiscordButton1TextKey
import com.metrolist.music.constants.DiscordButton1VisibleKey
import com.metrolist.music.constants.DiscordButton2TextKey
import com.metrolist.music.constants.DiscordButton2VisibleKey
import com.metrolist.music.constants.DiscordInfoDismissedKey
import com.metrolist.music.constants.DiscordNameKey
import com.metrolist.music.constants.DiscordShowProviderKey
import com.metrolist.music.constants.DiscordStatusKey
import com.metrolist.music.constants.DiscordUseDetailsKey
import com.metrolist.music.constants.DiscordUsernameKey
import com.metrolist.music.constants.EnableDiscordRPCKey
import com.metrolist.music.db.entities.Song
import com.metrolist.music.discord.DiscordRpcManager
import com.metrolist.music.ui.component.EnumDialog
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.InfoLabel
import com.metrolist.music.ui.component.Material3SettingsGroup
import com.metrolist.music.ui.component.Material3SettingsItem
import com.metrolist.music.ui.component.TextFieldDialog
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.utils.makeTimeString
import com.metrolist.music.utils.rememberEnumPreference
import com.metrolist.music.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class DiscordStatus { ONLINE, IDLE, DND }

private enum class DiscordActivityType { LISTENING, PLAYING, WATCHING, COMPETING }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DiscordSettings(
    navController: NavController,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val song by playerConnection.currentSong.collectAsStateWithLifecycle(null)
    val playbackState by playerConnection.playbackState.collectAsStateWithLifecycle()

    var position by rememberSaveable(playbackState) {
        mutableLongStateOf(playerConnection.player.currentPosition)
    }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    // Preferences
    var discordAccessToken by rememberPreference(DiscordAccessTokenKey, "")
    var discordUsername by rememberPreference(DiscordUsernameKey, "")
    var discordName by rememberPreference(DiscordNameKey, "")
    var discordAvatar by rememberPreference(DiscordAvatarKey, "")
    var infoDismissed by rememberPreference(DiscordInfoDismissedKey, false)

    val (discordRPC, onDiscordRPCChange) = rememberPreference(EnableDiscordRPCKey, true)
    val (useDetails, onUseDetailsChange) = rememberPreference(DiscordUseDetailsKey, false)
    val (showProvider, onShowProviderChange) = rememberPreference(DiscordShowProviderKey, true)
    val (advancedMode, onAdvancedModeChange) = rememberPreference(DiscordAdvancedModeKey, false)
    val (animatedCanvas, onAnimatedCanvasChange) = rememberPreference(DiscordAnimatedCanvasKey, false)
    var animatedCanvasQuality by rememberEnumPreference(
        DiscordAnimatedCanvasQualityKey,
        DiscordAnimatedCanvasQuality.NORMAL,
    )

    var discordStatus by rememberPreference(DiscordStatusKey, "online")
    var button1Text by rememberPreference(DiscordButton1TextKey, "")
    var button1Visible by rememberPreference(DiscordButton1VisibleKey, true)
    var button2Text by rememberPreference(DiscordButton2TextKey, "")
    var button2Visible by rememberPreference(DiscordButton2VisibleKey, true)
    var activityType by rememberPreference(DiscordActivityTypeKey, "listening")
    var activityName by rememberPreference(DiscordActivityNameKey, "")

    val isLoggedIn = remember(discordAccessToken) {
        discordAccessToken.isNotBlank()
    }
    val connectionStatus by DiscordRpcManager.connectionStatus.collectAsState()
    val statusText =
        when {
            connectionStatus == DiscordRpcManager.Status.Connected -> "Connected"
            connectionStatus == DiscordRpcManager.Status.Authorizing -> "Authorizing..."
            !DiscordRpcManager.isInitialized() -> "Not initialized"
            DiscordRpcManager.isAuthorized() -> "Authorized"
            else -> ""
        }
    var isBusy by remember { mutableStateOf(false) }

    var showStatusDialog by rememberSaveable { mutableStateOf(false) }
    var showActivityTypeDialog by rememberSaveable { mutableStateOf(false) }
    var showButton1TextDialog by rememberSaveable { mutableStateOf(false) }
    var showButton2TextDialog by rememberSaveable { mutableStateOf(false) }
    var showActivityNameDialog by rememberSaveable { mutableStateOf(false) }
    var showCanvasQualityDialog by rememberSaveable { mutableStateOf(false) }

    // Map string prefs to enums for dialogs
    val currentStatus =
        when (discordStatus) {
            "idle" -> DiscordStatus.IDLE
            "dnd" -> DiscordStatus.DND
            else -> DiscordStatus.ONLINE
        }
    val currentActivityType =
        when (activityType) {
            "playing" -> DiscordActivityType.PLAYING
            "watching" -> DiscordActivityType.WATCHING
            "competing" -> DiscordActivityType.COMPETING
            else -> DiscordActivityType.LISTENING
        }

    if (!DiscordRpcManager.isInitialized()) {
        DiscordRpcManager.init()
    }

    LaunchedEffect(Unit) {
        DiscordRpcManager.errors.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(discordAccessToken) {
        val token = discordAccessToken
        if (token.isBlank()) {
            discordUsername = ""
            discordName = ""
            discordAvatar = ""
            return@LaunchedEffect
        }
        if (!DiscordRpcManager.isReady()) {
            DiscordRpcManager.reconnectWithToken(token)
        }
        launch(Dispatchers.IO) {
            val user = DiscordRpcManager.fetchCurrentUser(token)
            withContext(Dispatchers.Main) {
                if (user != null) {
                    discordUsername = user.username
                    discordName = user.name
                    discordAvatar = user.avatar ?: ""
                } else {
                    discordUsername = ""
                    discordName = ""
                    discordAvatar = ""
                }
            }
        }
    }

    // Update playback position
    LaunchedEffect(playbackState) {
        if (playbackState == STATE_READY) {
            while (isActive) {
                delay(250)
                position = playerConnection.player.currentPosition
            }
        }
    }

    if (showStatusDialog) {
        EnumDialog(
            onDismiss = { showStatusDialog = false },
            onSelect = { selected ->
                discordStatus =
                    when (selected) {
                        DiscordStatus.IDLE -> "idle"
                        DiscordStatus.DND -> "dnd"
                        DiscordStatus.ONLINE -> "online"
                    }
                showStatusDialog = false
            },
            title = stringResource(R.string.discord_status),
            current = currentStatus,
            values = DiscordStatus.entries.toList(),
            valueText = {
                when (it) {
                    DiscordStatus.ONLINE -> stringResource(R.string.discord_status_online)
                    DiscordStatus.IDLE -> stringResource(R.string.discord_status_idle)
                    DiscordStatus.DND -> stringResource(R.string.discord_status_dnd)
                }
            },
        )
    }

    if (showActivityTypeDialog) {
        EnumDialog(
            onDismiss = { showActivityTypeDialog = false },
            onSelect = { selected ->
                activityType =
                    when (selected) {
                        DiscordActivityType.PLAYING -> "playing"
                        DiscordActivityType.WATCHING -> "watching"
                        DiscordActivityType.COMPETING -> "competing"
                        DiscordActivityType.LISTENING -> "listening"
                    }
                showActivityTypeDialog = false
            },
            title = stringResource(R.string.discord_activity_type),
            current = currentActivityType,
            values = DiscordActivityType.entries.toList(),
            valueText = {
                when (it) {
                    DiscordActivityType.LISTENING -> stringResource(R.string.discord_activity_listening)
                    DiscordActivityType.PLAYING -> stringResource(R.string.discord_activity_playing)
                    DiscordActivityType.WATCHING -> stringResource(R.string.discord_activity_watching)
                    DiscordActivityType.COMPETING -> stringResource(R.string.discord_activity_competing)
                }
            },
        )
    }

    if (showCanvasQualityDialog) {
        EnumDialog(
            onDismiss = { showCanvasQualityDialog = false },
            onSelect = { selected ->
                animatedCanvasQuality = selected
                showCanvasQualityDialog = false
            },
            title = stringResource(R.string.discord_animated_canvas_quality),
            current = animatedCanvasQuality,
            values = DiscordAnimatedCanvasQuality.entries.toList(),
            valueText = { quality ->
                when (quality) {
                    DiscordAnimatedCanvasQuality.LOW -> stringResource(R.string.discord_animated_canvas_quality_low)
                    DiscordAnimatedCanvasQuality.NORMAL -> stringResource(R.string.discord_animated_canvas_quality_normal)
                    DiscordAnimatedCanvasQuality.HIGH -> stringResource(R.string.discord_animated_canvas_quality_high)
                }
            },
        )
    }

    if (showButton1TextDialog) {
        TextFieldDialog(
            onDismiss = { showButton1TextDialog = false },
            onDone = {
                button1Text = it
                showButton1TextDialog = false
            },
            singleLine = true,
            initialTextFieldValue = TextFieldValue(button1Text),
            extraContent = {
                InfoLabel(text = stringResource(R.string.discord_button_text_variables))
            },
        )
    }

    if (showButton2TextDialog) {
        TextFieldDialog(
            onDismiss = { showButton2TextDialog = false },
            onDone = {
                button2Text = it
                showButton2TextDialog = false
            },
            singleLine = true,
            initialTextFieldValue = TextFieldValue(button2Text),
            extraContent = {
                InfoLabel(text = stringResource(R.string.discord_button_text_variables))
            },
        )
    }

    if (showActivityNameDialog) {
        TextFieldDialog(
            onDismiss = { showActivityNameDialog = false },
            onDone = {
                activityName = it
                showActivityNameDialog = false
            },
            singleLine = true,
            initialTextFieldValue = TextFieldValue(activityName),
            extraContent = {
                InfoLabel(text = stringResource(R.string.discord_activity_name_description))
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

        // Warning Card
        AnimatedVisibility(visible = !infoDismissed) {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.warning),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.discord_information_warning),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { infoDismissed = true },
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(stringResource(R.string.dismiss))
                        }
                    }
                }
            }
        }

        if (statusText.isNotEmpty()) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        // Profile Card (fully rounded)
        Card(
            shape = RoundedCornerShape(28.dp),
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .padding(
                            start = 20.dp,
                            end = 20.dp,
                            top = 20.dp,
                            bottom = if (isLoggedIn) 20.dp else 8.dp,
                        ).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Avatar with status dot
                Box(modifier = Modifier.size(56.dp)) {
                    if (isLoggedIn && discordAvatar.isNotEmpty()) {
                        AsyncImage(
                            model = discordAvatar,
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .size(56.dp)
                                    .clip(CircleShape),
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.discord),
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .size(36.dp)
                                    .align(Alignment.Center)
                                    .alpha(0.4f),
                        )
                    }
                    if (isLoggedIn) {
                        val statusColor =
                            when (discordStatus) {
                                "idle" -> MaterialTheme.colorScheme.tertiary
                                "dnd" -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.primary
                            }
                        Surface(
                            color = statusColor,
                            shape = CircleShape,
                            modifier =
                                Modifier
                                    .size(16.dp)
                                    .align(Alignment.BottomEnd)
                                    .border(
                                        2.dp,
                                        MaterialTheme.colorScheme.surfaceContainerHigh,
                                        CircleShape,
                                    ),
                            content = {},
                        )
                    }
                }

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text =
                            if (isLoggedIn) {
                                discordName.ifBlank { stringResource(R.string.discord_logged_in) }
                            } else {
                                stringResource(R.string.not_logged_in)
                            },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.alpha(if (isLoggedIn) 1f else 0.5f),
                    )
                    if (discordUsername.isNotEmpty()) {
                        Text(
                            text = "@$discordUsername",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!isLoggedIn) {
                        Text(
                            text = stringResource(R.string.discord_connect_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Only show logout inline when logged in
                if (isLoggedIn) {
                    OutlinedButton(onClick = {
                        discordName = ""
                        discordAccessToken = ""
                        discordUsername = ""
                        discordAvatar = ""
                        coroutineScope.launch(Dispatchers.IO) {
                            DiscordRpcManager.disconnect()
                        }
                    }) {
                        Text(stringResource(R.string.action_logout))
                    }
                }
            }

            // Login buttons below when not logged in
            if (!isLoggedIn) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        enabled = !isBusy,
                        onClick = {
                            isBusy = true
                            DiscordRpcManager.authorize { success ->
                                coroutineScope.launch {
                                    isBusy = false
                                    if (success) {
                                        val token = DiscordRpcManager.getAccessToken()
                                        if (token != null) {
                                            discordAccessToken = token
                                            val user = withContext(Dispatchers.IO) {
                                                DiscordRpcManager.fetchCurrentUser(token)
                                            }
                                            if (user != null) {
                                                discordUsername = user.username
                                                discordName = user.name
                                                discordAvatar = user.avatar ?: ""
                                            } else {
                                                discordUsername = ""
                                                discordName = ""
                                                discordAvatar = ""
                                            }
                                        }
                                    }
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.action_login))
                    }
                }
            }

            if (isBusy) {
                LinearProgressIndicator(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }

        // Options section (card-based)
        Material3SettingsGroup(
            title = stringResource(R.string.options),
            items =
                listOf(
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.enable_discord_rpc)) },
                        trailingContent = {
                            Switch(
                                checked = discordRPC,
                                onCheckedChange = onDiscordRPCChange,
                                enabled = isLoggedIn,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (discordRPC) R.drawable.check else R.drawable.close
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                }
                            )
                        },
                        enabled = isLoggedIn,
                        onClick = { if (isLoggedIn) onDiscordRPCChange(!discordRPC) },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.discord_use_details)) },
                        description = {
                            Text(stringResource(R.string.discord_use_details_description))
                        },
                        trailingContent = {
                            Switch(
                                checked = useDetails,
                                onCheckedChange = onUseDetailsChange,
                                enabled = isLoggedIn && discordRPC,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (useDetails) R.drawable.check else R.drawable.close
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                }
                            )
                        },
                        enabled = isLoggedIn && discordRPC,
                        onClick = {
                            if (isLoggedIn && discordRPC) onUseDetailsChange(!useDetails)
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.discord_show_provider)) },
                        description = {
                            Text(stringResource(R.string.discord_show_provider_description))
                        },
                        trailingContent = {
                            Switch(
                                checked = showProvider,
                                onCheckedChange = onShowProviderChange,
                                enabled = isLoggedIn && discordRPC,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (showProvider) R.drawable.check else R.drawable.close
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                }
                            )
                        },
                        enabled = isLoggedIn && discordRPC,
                        onClick = {
                            if (isLoggedIn && discordRPC) onShowProviderChange(!showProvider)
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.discord_animated_canvas)) },
                        description = {
                            Text(stringResource(R.string.discord_animated_canvas_description))
                        },
                        trailingContent = {
                            Switch(
                                checked = animatedCanvas,
                                onCheckedChange = onAnimatedCanvasChange,
                                enabled = isLoggedIn && discordRPC,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (animatedCanvas) R.drawable.check else R.drawable.close
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                }
                            )
                        },
                        enabled = isLoggedIn && discordRPC,
                        onClick = {
                            if (isLoggedIn && discordRPC) onAnimatedCanvasChange(!animatedCanvas)
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.discord_animated_canvas_quality)) },
                        description = {
                            Text(
                                when (animatedCanvasQuality) {
                                    DiscordAnimatedCanvasQuality.LOW -> {
                                        stringResource(R.string.discord_animated_canvas_quality_low_description)
                                    }

                                    DiscordAnimatedCanvasQuality.NORMAL -> {
                                        stringResource(R.string.discord_animated_canvas_quality_normal_description)
                                    }

                                    DiscordAnimatedCanvasQuality.HIGH -> {
                                        stringResource(R.string.discord_animated_canvas_quality_high_description)
                                    }
                                },
                            )
                        },
                        enabled = isLoggedIn && discordRPC && animatedCanvas,
                        onClick = {
                            if (isLoggedIn && discordRPC && animatedCanvas) {
                                showCanvasQualityDialog = true
                            }
                        },
                    ),
                    Material3SettingsItem(
                        title = { Text(stringResource(R.string.discord_advanced_mode)) },
                        description = {
                            Text(stringResource(R.string.discord_advanced_mode_description))
                        },
                        trailingContent = {
                            Switch(
                                checked = advancedMode,
                                onCheckedChange = onAdvancedModeChange,
                                enabled = isLoggedIn && discordRPC,
                                thumbContent = {
                                    Icon(
                                        painter = painterResource(
                                            id = if (advancedMode) R.drawable.check else R.drawable.close
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(SwitchDefaults.IconSize),
                                    )
                                }
                            )
                        },
                        enabled = isLoggedIn && discordRPC,
                        onClick = {
                            if (isLoggedIn && discordRPC) onAdvancedModeChange(!advancedMode)
                        },
                    ),
                ),
        )

        Spacer(Modifier.height(8.dp))

        // Advanced customization section
        AnimatedVisibility(visible = isLoggedIn && discordRPC && advancedMode) {
            Column(modifier = Modifier.animateContentSize()) {
                // Presence settings
                Material3SettingsGroup(
                    title = stringResource(R.string.discord_presence),
                    items =
                        listOf(
                            Material3SettingsItem(
                                title = { Text(stringResource(R.string.discord_status)) },
                                description = {
                                    Text(
                                        when (currentStatus) {
                                            DiscordStatus.ONLINE -> {
                                                stringResource(R.string.discord_status_online)
                                            }

                                            DiscordStatus.IDLE -> {
                                                stringResource(R.string.discord_status_idle)
                                            }

                                            DiscordStatus.DND -> {
                                                stringResource(R.string.discord_status_dnd)
                                            }
                                        },
                                    )
                                },
                                onClick = { showStatusDialog = true },
                            ),
                            Material3SettingsItem(
                                title = { Text(stringResource(R.string.discord_activity_type)) },
                                description = {
                                    Text(
                                        when (currentActivityType) {
                                            DiscordActivityType.LISTENING -> {
                                                stringResource(R.string.discord_activity_listening)
                                            }

                                            DiscordActivityType.PLAYING -> {
                                                stringResource(R.string.discord_activity_playing)
                                            }

                                            DiscordActivityType.WATCHING -> {
                                                stringResource(R.string.discord_activity_watching)
                                            }

                                            DiscordActivityType.COMPETING -> {
                                                stringResource(R.string.discord_activity_competing)
                                            }
                                        },
                                    )
                                },
                                onClick = { showActivityTypeDialog = true },
                            ),
                            Material3SettingsItem(
                                title = { Text(stringResource(R.string.discord_activity_name)) },
                                description = {
                                    Text(
                                        activityName.ifEmpty {
                                            stringResource(R.string.discord_activity_name_description)
                                        },
                                    )
                                },
                                onClick = { showActivityNameDialog = true },
                            ),
                        ),
                )

                Spacer(Modifier.height(8.dp))

                // Button customization
                Material3SettingsGroup(
                    title = stringResource(R.string.discord_buttons),
                    items =
                        listOf(
                            Material3SettingsItem(
                                title = { Text(stringResource(R.string.discord_button_1)) },
                                description = {
                                    Text(button1Text.ifEmpty { stringResource(R.string.discord_default_button_1) })
                                },
                                trailingContent = {
                                    Switch(
                                        checked = button1Visible,
                                        onCheckedChange = { button1Visible = it },
                                        thumbContent = {
                                            Icon(
                                                painter = painterResource(
                                                    id = if (button1Visible) R.drawable.check else R.drawable.close
                                                ),
                                                contentDescription = null,
                                                modifier = Modifier.size(SwitchDefaults.IconSize),
                                            )
                                        }
                                    )
                                },
                                onClick = { showButton1TextDialog = true },
                            ),
                            Material3SettingsItem(
                                title = { Text(stringResource(R.string.discord_button_2)) },
                                description = {
                                    Text(button2Text.ifEmpty { stringResource(R.string.discord_default_button_2) })
                                },
                                trailingContent = {
                                    Switch(
                                        checked = button2Visible,
                                        onCheckedChange = { button2Visible = it },
                                        thumbContent = {
                                            Icon(
                                                painter = painterResource(
                                                    id = if (button2Visible) R.drawable.check else R.drawable.close
                                                ),
                                                contentDescription = null,
                                                modifier = Modifier.size(SwitchDefaults.IconSize),
                                            )
                                        }
                                    )
                                },
                                onClick = { showButton2TextDialog = true },
                            ),
                        ),
                )

                // Variable hint
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.info),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.discord_button_text_variables),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }

        // Preview section
        Spacer(Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.discord_rpc_preview),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp, top = 8.dp),
        )

        RichPresence(
            song = song,
            currentPlaybackTimeMillis = position,
            activityType = activityType,
            activityName = activityName,
            button1Text = button1Text,
            button1Visible = button1Visible,
            button2Text = button2Text,
            button2Visible = button2Visible,
        )

        // Bottom padding for mini player
        Spacer(Modifier.height(24.dp))
    }

    TopAppBar(
        title = { Text(stringResource(R.string.discord_integration)) },
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RichPresence(
    song: Song?,
    currentPlaybackTimeMillis: Long = 0L,
    activityType: String = "listening",
    activityName: String = "",
    button1Text: String = "",
    button1Visible: Boolean = true,
    button2Text: String = "",
    button2Visible: Boolean = true,
) {
    val context = LocalContext.current
    val defaultButton1Text = stringResource(R.string.discord_default_button_1)
    val defaultButton2Text = stringResource(R.string.discord_default_button_2)
    val defaultButton2Url = stringResource(R.string.discord_default_button_2_url)
    val artworkUrl = song?.song?.thumbnailUrl?.takeIf { it.isNotBlank() }
    val durationMillis = song?.song?.duration?.takeIf { it > 0 }?.times(1000L)
    val safePositionMillis =
        durationMillis
            ?.let { currentPlaybackTimeMillis.coerceIn(0L, it) }
            ?: currentPlaybackTimeMillis.coerceAtLeast(0L)

    val activityLabel =
        when (activityType) {
            "playing" -> stringResource(R.string.discord_playing_metrolist)
            "watching" -> stringResource(R.string.discord_watching_metrolist)
            "competing" -> stringResource(R.string.discord_competing_metrolist)
            else -> stringResource(R.string.listening_to_metrolist)
        }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (activityName.isNotEmpty()) activityName else activityLabel,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Start,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(108.dp)) {
                    if (artworkUrl != null) {
                        AsyncImage(
                            model = artworkUrl,
                            contentDescription = null,
                            modifier =
                                Modifier
                                    .size(96.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .align(Alignment.TopStart),
                        )
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(3.dp),
                            modifier =
                                Modifier
                                    .size(96.dp)
                                    .align(Alignment.TopStart),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(R.drawable.music_note),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(34.dp),
                                )
                            }
                        }
                    }

                    song?.artists?.firstOrNull()?.thumbnailUrl?.takeIf { it.isNotBlank() }?.let {
                        Box(
                            modifier =
                                Modifier
                                    .border(
                                        2.dp,
                                        MaterialTheme.colorScheme.surfaceContainer,
                                        CircleShape,
                                    ).padding(2.dp)
                                    .align(Alignment.BottomEnd),
                        ) {
                            AsyncImage(
                                model = it,
                                contentDescription = null,
                                modifier =
                                    Modifier
                                        .size(32.dp)
                                        .clip(CircleShape),
                            )
                        }
                    }
                }

                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp),
                ) {
                    Text(
                        text = song?.song?.title ?: "Song Title",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Text(
                        text = song?.artists?.joinToString { it.name } ?: "Artist",
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    song?.album?.title?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.secondary,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (durationMillis != null) {
                        SongProgressBar(
                            currentTimeMillis = safePositionMillis,
                            durationMillis = durationMillis,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (button1Visible) {
                val resolvedButton1 =
                    if (song != null) {
                        resolveDiscordVariables(
                            button1Text.ifEmpty { defaultButton1Text },
                            song,
                        )
                    } else {
                        button1Text.ifEmpty { defaultButton1Text }
                    }
                OutlinedButton(
                    enabled = song != null,
                    onClick = {
                        val intent =
                            Intent(
                                Intent.ACTION_VIEW,
                                "https://music.youtube.com/watch?v=${song?.id}".toUri(),
                            )
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(resolvedButton1)
                }
            }

            if (button2Visible) {
                val resolvedButton2 =
                    if (song != null) {
                        resolveDiscordVariables(
                            button2Text.ifEmpty { defaultButton2Text },
                            song,
                        )
                    } else {
                        button2Text.ifEmpty { defaultButton2Text }
                    }
                OutlinedButton(
                    onClick = {
                        val intent =
                            Intent(
                                Intent.ACTION_VIEW,
                                defaultButton2Url.toUri(),
                            )
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(resolvedButton2)
                }
            }
        }
    }
}

private fun resolveDiscordVariables(text: String, song: Song): String =
    text
        .replace("{song_name}", song.song.title)
        .replace("{artist_name}", song.artists.joinToString { it.name })
        .replace("{album_name}", song.album?.title ?: "")

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SongProgressBar(
    currentTimeMillis: Long,
    durationMillis: Long,
) {
    val progress = if (durationMillis > 0) currentTimeMillis.toFloat() / durationMillis else 0f

    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(16.dp))

        LinearWavyProgressIndicator(
            progress = { progress },
            amplitude = { 1f },
            wavelength = 16.dp,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(6.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = makeTimeString(currentTimeMillis),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
                fontSize = 12.sp,
            )
            Text(
                text = makeTimeString(durationMillis),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                fontSize = 12.sp,
            )
        }
    }
}
