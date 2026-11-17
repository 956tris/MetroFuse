package dev.metrofuse.sdk

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable

@Composable
actual fun PluginAlertDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit,
) {
    AlertDialog(onDismissRequest, confirmButton, dismissButton = dismissButton, title = title, text = text)
}
