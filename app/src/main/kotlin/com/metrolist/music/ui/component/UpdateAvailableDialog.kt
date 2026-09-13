/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.music.utils.AppUpdateManager
import com.metrolist.music.utils.ReleaseAsset
import com.metrolist.music.utils.ReleaseInfo
import com.metrolist.music.utils.UpdateDownloadProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun UpdateAvailableDialog(
    releaseInfo: ReleaseInfo,
    releaseAsset: ReleaseAsset?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    val downloadFailedMessage = stringResource(R.string.update_download_failed)
    val installFailedMessage = stringResource(R.string.update_install_failed)
    val installPermissionMessage = stringResource(R.string.update_install_permission_required)
    var downloadProgress by remember(releaseInfo.tagName) { mutableStateOf<UpdateDownloadProgress?>(null) }
    var downloadedApk by remember(releaseInfo.tagName) { mutableStateOf<File?>(null) }
    var pendingInstall by remember(releaseInfo.tagName) { mutableStateOf<File?>(null) }
    var downloadJob by remember(releaseInfo.tagName) { mutableStateOf<Job?>(null) }
    var errorMessage by remember(releaseInfo.tagName) { mutableStateOf<String?>(null) }
    val isDownloading = downloadJob?.isActive == true

    val installPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val apk = pendingInstall
            pendingInstall = null
            if (apk != null && AppUpdateManager.canRequestPackageInstalls(context)) {
                AppUpdateManager.installApk(context, apk).onFailure { errorMessage = installFailedMessage }
            } else if (apk != null) {
                errorMessage = installPermissionMessage
            }
        }

    fun install(apk: File) {
        errorMessage = null
        if (AppUpdateManager.canRequestPackageInstalls(context)) {
            AppUpdateManager.installApk(context, apk).onFailure { errorMessage = installFailedMessage }
        } else {
            pendingInstall = apk
            installPermissionLauncher.launch(AppUpdateManager.unknownSourcesSettingsIntent(context))
        }
    }

    fun startDownload() {
        val asset = releaseAsset ?: return
        errorMessage = null
        downloadProgress = null
        downloadJob =
            coroutineScope.launch {
                AppUpdateManager
                    .downloadApk(context, asset) { progress ->
                        withContext(Dispatchers.Main.immediate) { downloadProgress = progress }
                    }.onSuccess { apk ->
                        downloadedApk = apk
                        install(apk)
                    }.onFailure {
                        errorMessage = downloadFailedMessage
                    }
                downloadJob = null
            }
    }

    fun dismiss() {
        downloadJob?.cancel()
        downloadJob = null
        onDismiss()
    }

    DisposableEffect(releaseInfo.tagName) {
        onDispose { downloadJob?.cancel() }
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text(stringResource(R.string.update_available_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.update_available_message, releaseInfo.versionName),
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (releaseInfo.description.isNotBlank()) {
                    Text(
                        text = releaseInfo.description,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (isDownloading) {
                    Spacer(modifier = Modifier.height(16.dp))
                    val progress = downloadProgress
                    if (progress?.fraction != null) {
                        LinearProgressIndicator(
                            progress = { progress.fraction!! },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (progress != null) {
                        val downloaded = AppUpdateManager.formatBytes(progress.downloadedBytes)
                        val total =
                            progress.totalBytes.takeIf { it > 0L }?.let(AppUpdateManager::formatBytes)
                                ?: stringResource(R.string.update_size_unknown)
                        Text(
                            text = stringResource(R.string.update_download_progress, downloaded, total),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                val visibleError =
                    errorMessage ?: if (releaseAsset == null) stringResource(R.string.update_apk_not_found) else null
                if (visibleError != null) {
                    Text(
                        text = visibleError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = releaseAsset != null && !isDownloading,
                onClick = { downloadedApk?.let(::install) ?: startDownload() },
            ) {
                Text(
                    when {
                        downloadedApk != null -> stringResource(R.string.update_install)
                        errorMessage != null -> stringResource(R.string.update_retry)
                        else -> stringResource(R.string.update_now)
                    },
                )
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { uriHandler.openUri(releaseInfo.releaseUrl) }) {
                    Text(stringResource(R.string.view_changelog))
                }
                TextButton(onClick = ::dismiss) {
                    Text(stringResource(if (isDownloading) R.string.cancel else R.string.not_now))
                }
            }
        },
    )
}

@Composable
fun UpdateAvailableBanner(
    releaseInfo: ReleaseInfo,
    releaseAsset: ReleaseAsset?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val downloadFailedMessage = stringResource(R.string.update_download_failed)
    val installFailedMessage = stringResource(R.string.update_install_failed)
    val installPermissionMessage = stringResource(R.string.update_install_permission_required)
    var downloadProgress by remember(releaseInfo.tagName) { mutableStateOf<UpdateDownloadProgress?>(null) }
    var downloadedApk by remember(releaseInfo.tagName) { mutableStateOf<File?>(null) }
    var pendingInstall by remember(releaseInfo.tagName) { mutableStateOf<File?>(null) }
    var downloadJob by remember(releaseInfo.tagName) { mutableStateOf<Job?>(null) }
    var errorMessage by remember(releaseInfo.tagName) { mutableStateOf<String?>(null) }
    var showChangelog by remember(releaseInfo.tagName) { mutableStateOf(false) }
    var dragDistance by remember(releaseInfo.tagName) { mutableStateOf(0f) }
    val isDownloading = downloadJob?.isActive == true

    val installPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val apk = pendingInstall
            pendingInstall = null
            if (apk != null && AppUpdateManager.canRequestPackageInstalls(context)) {
                AppUpdateManager.installApk(context, apk).onFailure { errorMessage = installFailedMessage }
            } else if (apk != null) {
                errorMessage = installPermissionMessage
            }
        }

    fun install(apk: File) {
        errorMessage = null
        if (AppUpdateManager.canRequestPackageInstalls(context)) {
            AppUpdateManager.installApk(context, apk).onFailure { errorMessage = installFailedMessage }
        } else {
            pendingInstall = apk
            installPermissionLauncher.launch(AppUpdateManager.unknownSourcesSettingsIntent(context))
        }
    }

    fun startDownload() {
        val asset = releaseAsset ?: return
        errorMessage = null
        downloadProgress = null
        downloadJob =
            coroutineScope.launch {
                AppUpdateManager
                    .downloadApk(context, asset) { progress ->
                        withContext(Dispatchers.Main.immediate) { downloadProgress = progress }
                    }
                    .onSuccess { apk ->
                        downloadedApk = apk
                        install(apk)
                    }.onFailure {
                        errorMessage = downloadFailedMessage
                    }
                downloadJob = null
            }
    }

    fun dismiss() {
        downloadJob?.cancel()
        downloadJob = null
        onDismiss()
    }

    DisposableEffect(releaseInfo.tagName) {
        onDispose { downloadJob?.cancel() }
    }

    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 4.dp,
        modifier =
            Modifier
                .fillMaxWidth()
                .pointerInput(releaseInfo.tagName) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, amount -> dragDistance += amount },
                        onDragCancel = { dragDistance = 0f },
                        onDragEnd = {
                            if (dragDistance < -48f) dismiss()
                            dragDistance = 0f
                        },
                    )
                },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 12.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.update),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.update_available_message, releaseInfo.versionName),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    TextButton(
                        onClick = { showChangelog = !showChangelog },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        modifier = Modifier.height(28.dp),
                    ) {
                        Text(stringResource(R.string.view_changelog))
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(
                        enabled = releaseAsset != null && !isDownloading,
                        onClick = { downloadedApk?.let(::install) ?: startDownload() },
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text(
                            when {
                                downloadedApk != null -> stringResource(R.string.update_install)
                                errorMessage != null -> stringResource(R.string.update_retry)
                                else -> stringResource(R.string.update_now)
                            },
                        )
                    }
                    TextButton(
                        onClick = ::dismiss,
                        modifier = Modifier.height(32.dp),
                    ) {
                        Text(stringResource(if (isDownloading) R.string.cancel else R.string.not_now))
                    }
                }
            }

            if (isDownloading) {
                val progress = downloadProgress
                if (progress?.fraction != null) {
                    LinearProgressIndicator(
                        progress = { progress.fraction!! },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (progress != null) {
                    val downloaded = AppUpdateManager.formatBytes(progress.downloadedBytes)
                    val total =
                        progress.totalBytes.takeIf { it > 0L }?.let(AppUpdateManager::formatBytes)
                            ?: stringResource(R.string.update_size_unknown)
                    Text(
                        text = stringResource(R.string.update_download_progress, downloaded, total),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    Text(
                        text =
                            stringResource(
                                R.string.update_download_stats,
                                AppUpdateManager.formatBytes(progress.bytesPerSecond),
                                AppUpdateManager.formatDuration(progress.elapsedSeconds),
                                AppUpdateManager.formatDuration(progress.remainingSeconds),
                            ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
            }

            AnimatedVisibility(visible = showChangelog && releaseInfo.description.isNotBlank()) {
                Text(
                    text = releaseInfo.description,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }

            val visibleError =
                errorMessage ?: if (releaseAsset == null) stringResource(R.string.update_apk_not_found) else null
            if (visibleError != null) {
                Text(
                    text = visibleError,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
