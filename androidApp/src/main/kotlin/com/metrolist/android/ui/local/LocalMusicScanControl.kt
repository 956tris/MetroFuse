/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.ui.local

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.android.extensions.findActivity
import com.metrolist.android.local.AndroidLocalMusicScanner
import com.metrolist.android.local.LocalMusicScanState
import com.metrolist.android.local.LocalMusicScanStatus
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.local_music_open_settings
import com.metrolist.shared.generated.resources.local_music_permission_required
import com.metrolist.shared.generated.resources.local_music_scan
import com.metrolist.shared.generated.resources.local_music_scan_complete
import com.metrolist.shared.generated.resources.local_music_scan_description
import com.metrolist.shared.generated.resources.local_music_scan_failed
import com.metrolist.shared.generated.resources.local_music_scanning
import com.metrolist.shared.generated.resources.local_music_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

data class LocalMusicScanControl(
    val state: LocalMusicScanState,
    val label: String,
    val hasPermission: Boolean,
    val onClick: () -> Unit,
)

@Composable
fun rememberLocalMusicScanControl(onComplete: () -> Unit = {}): LocalMusicScanControl {
    val context = LocalContext.current
    val scanner: AndroidLocalMusicScanner = koinInject()
    val state by scanner.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val currentOnComplete by rememberUpdatedState(onComplete)
    val permissionMessage = stringResource(Res.string.local_music_permission_required)
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var permissionBlocked by rememberSaveable { mutableStateOf(false) }
    var returningFromSettings by rememberSaveable { mutableStateOf(false) }

    fun scan() {
        scope.launch {
            val result = scanner.scan()
            val message =
                when (result.status) {
                    LocalMusicScanStatus.COMPLETE -> {
                        currentOnComplete()
                        getString(Res.string.local_music_scan_complete, result.found, result.imported)
                    }

                    LocalMusicScanStatus.PERMISSION_REQUIRED -> {
                        permissionMessage
                    }

                    LocalMusicScanStatus.FAILED -> {
                        getString(Res.string.local_music_scan_failed)
                    }

                    else -> {
                        null
                    }
                }
            if (message != null) Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                permissionBlocked = false
                scan()
            } else {
                permissionBlocked = context.findActivity()?.let {
                    !ActivityCompat.shouldShowRequestPermissionRationale(it, scanner.permission)
                } == true
                Toast.makeText(context, permissionMessage, Toast.LENGTH_LONG).show()
            }
        }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, scanner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && returningFromSettings) {
                    returningFromSettings = false
                    if (scanner.hasPermission()) {
                        permissionBlocked = false
                        scan()
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val label =
        when {
            state.status == LocalMusicScanStatus.SCANNING -> stringResource(Res.string.local_music_scanning)
            permissionBlocked && !scanner.hasPermission() -> stringResource(Res.string.local_music_open_settings)
            else -> stringResource(Res.string.local_music_scan)
        }
    return LocalMusicScanControl(state, label, scanner.hasPermission()) {
        if (scanner.hasPermission()) {
            scan()
        } else if (permissionRequested && permissionBlocked) {
            returningFromSettings = true
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        } else {
            permissionRequested = true
            permissionLauncher.launch(scanner.permission)
        }
    }
}

@Composable
fun LocalMusicScanCard(
    modifier: Modifier = Modifier,
    control: LocalMusicScanControl = rememberLocalMusicScanControl(),
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(Res.string.local_music_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.local_music_scan_description), style = MaterialTheme.typography.bodyMedium)
            when (control.state.status) {
                LocalMusicScanStatus.COMPLETE -> {
                    Text(
                        stringResource(Res.string.local_music_scan_complete, control.state.found, control.state.imported),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                LocalMusicScanStatus.PERMISSION_REQUIRED -> {
                    Text(stringResource(Res.string.local_music_permission_required))
                }

                LocalMusicScanStatus.FAILED -> {
                    Text(stringResource(Res.string.local_music_scan_failed), color = MaterialTheme.colorScheme.error)
                }

                LocalMusicScanStatus.SCANNING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                LocalMusicScanStatus.IDLE -> {
                    Unit
                }
            }
            OutlinedButton(onClick = control.onClick, enabled = control.state.status != LocalMusicScanStatus.SCANNING) {
                Text(control.label)
            }
        }
    }
}
