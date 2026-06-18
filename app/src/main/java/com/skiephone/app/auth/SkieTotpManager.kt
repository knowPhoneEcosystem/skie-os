package com.skiephone.app.auth

import android.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * TOTP (RFC 6238) implementation.
 * Secret is Base32-encoded, stored in RTDB + EncryptedSharedPrefs.
 * Compatible with Google Authenticator and Microsoft Authenticator.
 */
object SkieTotpManager {

    private const val DIGITS = 6
    private const val STEP_SECONDS = 30L
    private const val WINDOW = 1 // allow 1 step tolerance (±30s clock skew)

    // Base32 alphabet
    private const val BASE32_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /**
     * Generate a new random Base32 secret (160 bits = 20 bytes).
     * Store the returned string in RTDB + EncryptedSharedPrefs immediately.
     */
    fun generateSecret(): String {
        val bytes = ByteArray(20).also { java.security.SecureRandom().nextBytes(it) }
        return encodeBase32(bytes)
    }

    /**
     * Build the otpauth:// URI for QR code display.
     * Scan this URI with Google/Microsoft Authenticator.
     */
    fun buildOtpAuthUri(secret: String, deviceId: String): String {
        val label = "SkieOS:$deviceId"
        return "otpauth://totp/${label}?secret=$secret&issuer=SkieOS&digits=$DIGITS&period=$STEP_SECONDS"
    }

    /**
     * Verify a 6-digit code against the secret.
     * Allows ±WINDOW step tolerance for clock skew.
     * Returns true if valid.
     */
    fun verify(secret: String, code: String): Boolean {
        val trimmed = code.trim()
        if (trimmed.length != DIGITS) return false
        val inputCode = trimmed.toLongOrNull() ?: return false
        val secretBytes = try { decodeBase32(secret) } catch (e: Exception) { return false }
        val currentStep = System.currentTimeMillis() / 1000L / STEP_SECONDS
        for (offset in -WINDOW..WINDOW) {
            if (computeTotp(secretBytes, currentStep + offset) == inputCode) return true
        }
        return false
    }

    private fun computeTotp(secret: ByteArray, step: Long): Long {
        val msg = ByteArray(8)
        var s = step
        for (i in 7 downTo 0) {
            msg[i] = (s and 0xFF).toByte()
            s = s shr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "RAW"))
        val hash = mac.doFinal(msg)
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
                ((hash[offset + 1].toInt() and 0xFF) shl 16) or
                ((hash[offset + 2].toInt() and 0xFF) shl 8) or
                (hash[offset + 3].toInt() and 0xFF)
        return (binary % 10.0.pow(DIGITS).toLong())
    }

    private fun encodeBase32(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(BASE32_CHARS[(buffer shr bitsLeft) and 0x1F])
            }
        }
        if (bitsLeft > 0) sb.append(BASE32_CHARS[(buffer shl (5 - bitsLeft)) and 0x1F])
        return sb.toString()
    }

    private fun decodeBase32(input: String): ByteArray {
        val upper = input.uppercase().trimEnd('=')
        var buffer = 0
        var bitsLeft = 0
        val result = mutableListOf<Byte>()
        for (c in upper) {
            val idx = BASE32_CHARS.indexOf(c)
            if (idx < 0) continue
            buffer = (buffer shl 5) or idx
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                result.add(((buffer shr bitsLeft) and 0xFF).toByte())
            }
        }
        return result.toByteArray()
    }
}