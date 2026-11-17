package dev.metrofuse.sdk

import androidx.compose.runtime.Composable

/** Platform-compiled dialog entry point for plugins compiled against the desktop SDK. */
@Composable
expect fun PluginAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
)
