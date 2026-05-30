/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.qobuz

import android.os.Looper
import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

object SquidAltchaWebViewSolver {
    private const val TAG = "SquidAltcha"
    private const val VERIFIED_COOKIE_TTL_MS = 25 * 60 * 1000L
    private const val MAX_COUNTER = 2_000_000

    private val jsonMediaType = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val solveLock = Any()

    @Volatile
    private var cachedCookie: VerifiedCookie? = null

    fun cookieHeaderOrNull(): String? =
        cachedCookie
            ?.takeIf { it.expiresAtMs > System.currentTimeMillis() + 10_000L }
            ?.header

    fun invalidate() {
        cachedCookie = null
    }

    fun solve(baseUrl: String, userAgent: String): String? =
        synchronized(solveLock) {
            cookieHeaderOrNull()?.let { return@synchronized it }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Timber.tag(TAG).w("Refusing to block main thread for Squid ALTCHA")
                return@synchronized null
            }

            val challenge = fetchChallenge(baseUrl, userAgent) ?: return@synchronized null
            val solution = runCatching { solveChallenge(challenge) }
                .getOrElse { error ->
                    Timber.tag(TAG).w(error, "Failed to solve Squid ALTCHA challenge")
                    null
                }
                ?: return@synchronized null
            val cookie = verifyChallenge(baseUrl, userAgent, challenge, solution)
                ?: return@synchronized null

            cachedCookie = VerifiedCookie(
                header = cookie,
                expiresAtMs = System.currentTimeMillis() + VERIFIED_COOKIE_TTL_MS,
            )
            cookie
        }

    private fun fetchChallenge(
        baseUrl: String,
        userAgent: String,
    ): Challenge? {
        val url = "$baseUrl/api/altcha/challenge".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("ts", System.currentTimeMillis().toString())
            ?.build()
            ?: return null
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("Origin", baseUrl)
            .header("Referer", "$baseUrl/")
            .header("User-Agent", userAgent)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val payload = response.body.string()
                if (!response.isSuccessful) {
                    Timber.tag(TAG).w("Squid ALTCHA challenge HTTP ${response.code}: ${payload.take(160)}")
                    return@use null
                }
                val root = JSONObject(payload)
                val parameters = root.optJSONObject("parameters") ?: return@use null
                val signature = root.stringOrNull("signature") ?: return@use null
                Challenge(
                    parameters = AltchaParameters(
                        algorithm = parameters.stringOrNull("algorithm") ?: "SHA-256",
                        cost = parameters.intOrNull("cost") ?: 1,
                        expiresAt = parameters.longOrNull("expiresAt"),
                        keyLength = parameters.intOrNull("keyLength") ?: 32,
                        keyPrefix = parameters.stringOrNull("keyPrefix") ?: return@use null,
                        nonce = parameters.stringOrNull("nonce") ?: return@use null,
                        salt = parameters.stringOrNull("salt") ?: return@use null,
                    ),
                    signature = signature,
                )
            }
        }.getOrElse { error ->
            Timber.tag(TAG).w(error, "Failed to fetch Squid ALTCHA challenge")
            null
        }
    }

    private fun solveChallenge(challenge: Challenge): Solution? {
        val parameters = challenge.parameters
        val startedNs = System.nanoTime()
        for (counter in 0 until MAX_COUNTER) {
            val derivedKey = deriveKey(parameters, counter)
            val derivedKeyHex = derivedKey.toHexString()
            if (derivedKeyHex.startsWith(parameters.keyPrefix, ignoreCase = true)) {
                return Solution(
                    counter = counter,
                    derivedKeyHex = derivedKeyHex,
                    timeMs = ((System.nanoTime() - startedNs) / 100_000L) / 10.0,
                )
            }
        }
        Timber.tag(TAG).w("Squid ALTCHA had no solution below $MAX_COUNTER")
        return null
    }

    private fun verifyChallenge(
        baseUrl: String,
        userAgent: String,
        challenge: Challenge,
        solution: Solution,
    ): String? {
        val url = "$baseUrl/api/altcha/verify".toHttpUrlOrNull() ?: return null
        val verifyBody = JSONObject()
            .put("payload", encodedPayload(challenge, solution))
            .toString()
            .toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(url)
            .post(verifyBody)
            .header("Accept", "application/json,text/plain,*/*")
            .header("Origin", baseUrl)
            .header("Referer", "$baseUrl/")
            .header("User-Agent", userAgent)
            .build()

        return runCatching {
            client.newCall(request).execute().use { response ->
                val payload = response.body.string()
                if (!response.isSuccessful) {
                    Timber.tag(TAG).w("Squid ALTCHA verify HTTP ${response.code}: ${payload.take(180)}")
                    return@use null
                }
                val root = runCatching { JSONObject(payload) }.getOrNull()
                if (root?.optBoolean("success", false) != true) {
                    Timber.tag(TAG).w("Squid ALTCHA verify rejected: ${payload.take(180)}")
                    return@use null
                }
                response.headers("Set-Cookie")
                    .mapNotNull { it.substringBefore(';').trim().takeIf { value -> value.isNotBlank() } }
                    .distinct()
                    .joinToString("; ")
                    .takeIf { it.isNotBlank() }
                    ?: run {
                        Timber.tag(TAG).w("Squid ALTCHA verified but returned no cookie")
                        null
                    }
            }
        }.getOrElse { error ->
            Timber.tag(TAG).w(error, "Failed to verify Squid ALTCHA challenge")
            null
        }
    }

    private fun deriveKey(
        parameters: AltchaParameters,
        counter: Int,
    ): ByteArray {
        val nonce = parameters.nonce.hexToByteArray()
        val salt = parameters.salt.hexToByteArray()
        val password = ByteArray(nonce.size + 4)
        nonce.copyInto(password)
        password[nonce.size] = (counter ushr 24).toByte()
        password[nonce.size + 1] = (counter ushr 16).toByte()
        password[nonce.size + 2] = (counter ushr 8).toByte()
        password[nonce.size + 3] = counter.toByte()

        var derivedKey = ByteArray(0)
        repeat(parameters.cost.coerceAtLeast(1)) { index ->
            val digest = MessageDigest.getInstance(parameters.algorithm)
            val data = if (index == 0) salt + password else derivedKey
            val digestBytes = digest.digest(data)
            derivedKey = if (parameters.keyLength < digestBytes.size) {
                digestBytes.copyOf(parameters.keyLength)
            } else {
                digestBytes
            }
        }
        return derivedKey
    }

    private fun encodedPayload(
        challenge: Challenge,
        solution: Solution,
    ): String {
        val parameters = challenge.parameters
        val payload = JSONObject()
            .put(
                "challenge",
                JSONObject()
                    .put(
                        "parameters",
                        JSONObject()
                            .put("algorithm", parameters.algorithm)
                            .put("cost", parameters.cost)
                            .put("keyLength", parameters.keyLength)
                            .put("keyPrefix", parameters.keyPrefix)
                            .put("nonce", parameters.nonce)
                            .put("salt", parameters.salt)
                            .apply {
                                parameters.expiresAt?.let { put("expiresAt", it) }
                            },
                    )
                    .put("signature", challenge.signature),
            )
            .put(
                "solution",
                JSONObject()
                    .put("counter", solution.counter)
                    .put("derivedKey", solution.derivedKeyHex)
                    .put("time", solution.timeMs),
            )
        return Base64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0) { "Hex string must have an even length" }
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }

    private fun JSONObject.stringOrNull(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).trim().takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun JSONObject.intOrNull(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return runCatching { getInt(name) }.getOrElse {
            optString(name).trim().toIntOrNull()
        }
    }

    private fun JSONObject.longOrNull(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        return runCatching { getLong(name) }.getOrElse {
            optString(name).trim().toLongOrNull()
        }
    }

    private data class Challenge(
        val parameters: AltchaParameters,
        val signature: String,
    )

    private data class AltchaParameters(
        val algorithm: String,
        val cost: Int,
        val expiresAt: Long?,
        val keyLength: Int,
        val keyPrefix: String,
        val nonce: String,
        val salt: String,
    )

    private data class Solution(
        val counter: Int,
        val derivedKeyHex: String,
        val timeMs: Double,
    )

    private data class VerifiedCookie(
        val header: String,
        val expiresAtMs: Long,
    )
}
