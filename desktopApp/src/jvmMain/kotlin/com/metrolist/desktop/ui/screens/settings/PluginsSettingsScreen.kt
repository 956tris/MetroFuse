package com.metrolist.desktop.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.metrolist.shared.plugins.MetroFuseHost
import com.metrolist.shared.plugins.PluginManagerScreen
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PluginsSettingsScreen() {
    val scope = rememberCoroutineScope()
    var importError by remember { mutableStateOf<String?>(null) }
    var importInProgress by remember { mutableStateOf(false) }
    PluginManagerScreen(onImport = {
        scope.launch {
            importError = null
            importInProgress = true
            try {
                val file = FileKit.openFilePicker(type = FileKitType.File(listOf("mfp", "jar")))?.file
                if (file != null) {
                    withContext(Dispatchers.IO) {
                        val runtime = MetroFuseHost.runtime
                        runtime.initialize()
                        file.inputStream().use { runtime.installOrUpdate(it, file.name) }
                    }
                }
            } catch (
                cancelled: CancellationException,
            ) {
                throw cancelled
            } catch (
                failure: Exception,
            ) {
                importError = failure.message ?: failure.toString()
            } finally {
                importInProgress = false
            }
        }
    }, importError = importError, importInProgress = importInProgress, modifier = Modifier.fillMaxSize())
}
