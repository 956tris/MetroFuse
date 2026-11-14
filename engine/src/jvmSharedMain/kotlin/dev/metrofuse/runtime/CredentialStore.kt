package dev.metrofuse.runtime

import dev.metrofuse.api.LoginCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

interface CredentialStore {
    suspend fun read(owner: String): LoginCredentials?

    suspend fun write(
        owner: String,
        credentials: LoginCredentials?,
    )
}

object UnavailableCredentialStore : CredentialStore {
    override suspend fun read(owner: String): LoginCredentials? = null

    override suspend fun write(
        owner: String,
        credentials: LoginCredentials?,
    ) {
        if (credentials == null) return
        throw UnsupportedOperationException("Secure credential storage is unavailable")
    }
}

interface CredentialProtector {
    fun encrypt(
        owner: String,
        cleartext: ByteArray,
    ): ByteArray

    fun decrypt(
        owner: String,
        encrypted: ByteArray,
    ): ByteArray
}

/** Only ciphertext is written. The protector binds each blob to its plugin namespace. */
class EncryptedCredentialStore(
    private val directory: File,
    private val protector: CredentialProtector,
) : CredentialStore {
    private val gate = Mutex()

    override suspend fun read(owner: String): LoginCredentials? =
        withContext(Dispatchers.IO) {
            gate.withLock {
                val file = fileFor(owner)
                if (!file.exists()) return@withLock null
                require(file.length() in 1..128 * 1024)
                val cleartext = protector.decrypt(owner, file.readBytes())
                try {
                    codec.decodeFromString<LoginCredentials>(cleartext.decodeToString())
                } finally {
                    cleartext.fill(0)
                }
            }
        }

    override suspend fun write(
        owner: String,
        credentials: LoginCredentials?,
    ) = withContext(Dispatchers.IO) {
        gate.withLock {
            val file = fileFor(owner)
            if (credentials == null) {
                Files.deleteIfExists(file.toPath())
                return@withLock
            }
            require(directory.mkdirs() || directory.isDirectory)
            val cleartext = codec.encodeToString(credentials).encodeToByteArray()
            val encrypted =
                try {
                    require(cleartext.size <= 64 * 1024)
                    protector.encrypt(owner, cleartext)
                } finally {
                    cleartext.fill(0)
                }
            val temporary = File(directory, "${file.name}.tmp")
            try {
                temporary.outputStream().use { output ->
                    output.write(encrypted)
                    output.fd.sync()
                }
                try {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temporary.delete()
            }
        }
    }

    private fun fileFor(owner: String): File {
        require(owner.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        val digest = MessageDigest.getInstance("SHA-256").digest(owner.encodeToByteArray())
        return File(directory, digest.joinToString("") { "%02x".format(it) } + ".bin")
    }
}
