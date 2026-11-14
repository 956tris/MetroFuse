package dev.metrofuse.runtime

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

fun androidCredentialStore(directory: File): CredentialStore =
    EncryptedCredentialStore(
        directory,
        object : CredentialProtector {
            private fun key(owner: String): SecretKey {
                val hash = MessageDigest.getInstance("SHA-256").digest(owner.encodeToByteArray()).joinToString("") { "%02x".format(it) }
                val alias = "metrofuse.plugin.$hash"
                val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                (store.getKey(alias, null) as? SecretKey)?.let { return it }
                return KeyGenerator
                    .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                    .apply {
                        init(
                            KeyGenParameterSpec
                                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .setKeySize(256)
                                .build(),
                        )
                    }.generateKey()
            }

            override fun encrypt(
                owner: String,
                cleartext: ByteArray,
            ): ByteArray {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, key(owner))
                cipher.updateAAD(owner.encodeToByteArray())
                return cipher.iv + cipher.doFinal(cleartext)
            }

            override fun decrypt(
                owner: String,
                encrypted: ByteArray,
            ): ByteArray {
                require(encrypted.size > 28)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(owner), GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
                cipher.updateAAD(owner.encodeToByteArray())
                return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
            }
        },
    )
