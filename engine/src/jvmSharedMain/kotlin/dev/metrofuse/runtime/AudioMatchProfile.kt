package dev.metrofuse.runtime

import java.security.MessageDigest

/** Distinguishes failed recordings without putting provider URLs or track IDs in diagnostic profile keys. */
internal fun audioMatchProfile(
    provider: String,
    trackId: String,
): String {
    val digest =
        MessageDigest
            .getInstance("SHA-256")
            .digest(trackId.encodeToByteArray())
            .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
    return "$provider@$digest"
}
