package dev.metrofuse.runtime

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Crypt32Util
import com.sun.jna.platform.win32.WinCrypt
import java.io.File

fun desktopCredentialStore(directory: File): CredentialStore {
    // Fail closed on platforms without an implemented OS-backed credential protector.
    if (!Platform.isWindows()) return UnavailableCredentialStore
    return EncryptedCredentialStore(
        directory,
        object : CredentialProtector {
            override fun encrypt(
                owner: String,
                cleartext: ByteArray,
            ): ByteArray =
                Crypt32Util.cryptProtectData(cleartext, owner.encodeToByteArray(), WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null, null)

            override fun decrypt(
                owner: String,
                encrypted: ByteArray,
            ): ByteArray = Crypt32Util.cryptUnprotectData(encrypted, owner.encodeToByteArray(), WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null)
        },
    )
}
