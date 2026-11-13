package com.ayinbox

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/** Verifies the `X-Ayinbox-Signature` header of a webhook delivery. Needs no client or API key. */
object WebhookSignature {
    const val HEADER = "X-Ayinbox-Signature"
    const val DEFAULT_TOLERANCE_SECONDS = 300L

    /**
     * True when [header] carries a `v1` HMAC-SHA256 of `"<t>.<payload>"` under [secret] (with its `whsec_` prefix)
     * and `t` is within [toleranceSeconds] of [nowSeconds]. Pass the raw body bytes, before any JSON parsing.
     */
    fun verify(
        payload: ByteArray,
        header: String,
        secret: String,
        toleranceSeconds: Long = DEFAULT_TOLERANCE_SECONDS,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Boolean {
        require(toleranceSeconds > 0) { "toleranceSeconds must be positive" }
        var timestamp: Long? = null
        val signatures = mutableListOf<String>()
        for (part in header.split(',')) {
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            val key = part.substring(0, idx).trim()
            val value = part.substring(idx + 1).trim()
            when (key) {
                "t" -> timestamp = value.toLongOrNull()
                "v1" -> signatures.add(value)
            }
        }
        val t = timestamp ?: return false
        if (signatures.isEmpty()) return false
        if (abs(nowSeconds - t) > toleranceSeconds) return false

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        mac.update("$t.".toByteArray(Charsets.UTF_8))
        val expected = hex(mac.doFinal(payload)).toByteArray(Charsets.US_ASCII)
        var ok = false
        for (sig in signatures) {
            // Constant-time compare; check every candidate.
            if (MessageDigest.isEqual(expected, sig.lowercase().toByteArray(Charsets.US_ASCII))) ok = true
        }
        return ok
    }

    fun verify(
        payload: String,
        header: String,
        secret: String,
        toleranceSeconds: Long = DEFAULT_TOLERANCE_SECONDS,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): Boolean = verify(payload.toByteArray(Charsets.UTF_8), header, secret, toleranceSeconds, nowSeconds)

    /** Verifies, then parses the delivery. Throws [SignatureVerificationException] when it does not verify. */
    fun constructEvent(
        payload: ByteArray,
        header: String,
        secret: String,
        toleranceSeconds: Long = DEFAULT_TOLERANCE_SECONDS,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): WebhookEvent {
        if (!verify(payload, header, secret, toleranceSeconds, nowSeconds)) {
            throw SignatureVerificationException("Webhook signature did not verify")
        }
        return AyInbox.json.decodeFromString(WebhookEvent.serializer(), payload.toString(Charsets.UTF_8))
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
