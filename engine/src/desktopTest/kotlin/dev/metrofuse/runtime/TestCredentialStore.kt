package dev.metrofuse.runtime

import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** Portable runtime fixture. The test key stays in memory; platform storage has its own test. */
internal fun testCredentialStore(directory: File): CredentialStore {
    val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    val protector =
        object : CredentialProtector {
            override fun encrypt(
                owner: String,
                cleartext: ByteArray,
            ): ByteArray {
                val iv = ByteArray(12).also(SecureRandom()::nextBytes)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
                cipher.updateAAD(owner.encodeToByteArray())
                return iv + cipher.doFinal(cleartext)
            }

            override fun decrypt(
                owner: String,
                encrypted: ByteArray,
            ): ByteArray {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
                cipher.updateAAD(owner.encodeToByteArray())
                return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
            }
        }
    return EncryptedCredentialStore(directory, protector)
}
