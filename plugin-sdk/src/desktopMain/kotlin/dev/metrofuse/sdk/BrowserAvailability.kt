package dev.metrofuse.sdk

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import java.util.concurrent.ConcurrentHashMap

// WebKit starts native threads; keep its libraries mapped for the process lifetime.
private val browserLibraries = ConcurrentHashMap<String, NativeLibrary>()

private interface BrowserLibC : Library {
    fun setenv(
        name: String,
        value: String,
        overwrite: Int,
    ): Int
}

internal fun isBrowserAvailable(): Boolean {
    if (!System.getProperty("os.name").lowercase().contains("linux")) return true
    return try {
        val environment = Native.load("c", BrowserLibC::class.java)
        if (environment.setenv("WEBKIT_DMABUF_RENDERER_FORCE_SHM", "1", 0) != 0) return false
        listOf("libwebkit2gtk-4.1.so.0", "libjavascriptcoregtk-4.1.so.0").forEach {
            browserLibraries.computeIfAbsent(it, NativeLibrary::getInstance)
        }
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }
}
