package com.metrolist.android.ui.screens.settings

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.plugins_import_filename_error
import com.metrolist.shared.generated.resources.plugins_import_open_error
import com.metrolist.shared.generated.resources.plugins_title
import com.metrolist.shared.plugins.MetroFuseHost
import com.metrolist.shared.plugins.PluginManagerScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

@Composable
fun PluginsScreen(onBack: () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var importError by remember { mutableStateOf<String?>(null) }
    var importInProgress by remember { mutableStateOf(false) }
    val filenameError = stringResource(Res.string.plugins_import_filename_error)
    val openError = stringResource(Res.string.plugins_import_open_error)
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                scope.launch {
                    importError = null
                    importInProgress = true
                    try {
                        withContext(Dispatchers.IO) {
                            val name =
                                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                                    if (it.moveToFirst()) it.getString(0) else null
                                }
                            require(!name.isNullOrBlank()) { filenameError }
                            val runtime = MetroFuseHost.runtime
                            runtime.initialize()
                            requireNotNull(resolver.openInputStream(uri)) { openError }.use { runtime.installOrUpdate(it, name) }
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
            }
        }
    SettingsSubScreen(stringResource(Res.string.plugins_title), onBack, contentScrollable = false) {
        PluginManagerScreen(onImport = {
            picker.launch(arrayOf("*/*"))
        }, importError = importError, importInProgress = importInProgress, modifier = Modifier.fillMaxSize())
    }
}
