package dev.metrofuse.runtime

import dev.metrofuse.api.NativePlugin
import androidx.compose.ui.graphics.ImageBitmap

class NativeHandle(
    val plugin: NativePlugin,
    val readAsset: (String) -> ByteArray = { error("Native resource reader unavailable") },
    val platformResources: Any? = null,
    val requiresRestartForReplacement: Boolean = false,
    val close: () -> Unit,
)
expect fun loadNative(path: String, entryClass: String): NativeHandle
expect fun decodeArtwork(bytes: ByteArray): ImageBitmap
