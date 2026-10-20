package com.softppj.hometv

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Verifies a server-signed offline lease. This is intentionally fail-closed:
 * an unsigned /validate response must NEVER grant offline access.
 *
 * Expected server envelope:
 * { "lease": "<base64url UTF-8 JSON>", "signature": "<base64url RSA-SHA256 signature>" }
 * Signed JSON: { "device": "...", "expiresAt": <epoch millis>,
 *                "issuedAt": <epoch millis>, "licenseId": "..." }
 *
 * The server public key must be embedded in a future approved app build.
 * No server signing contract or public key exists in the live Cloudflare service yet.
 */
internal class SignedLicenseCache(
    private val prefs: SharedPreferences,
    private val deviceId: String,
    private val publicKeyDerBase64: String
) {
    companion object {
        const val RECHECK_MS = 24L * 60 * 60 * 1000
        const val MAX_OFFLINE_MS = 3L * 24 * 60 * 60 * 1000
        private const val ROLLBACK_TOLERANCE_MS = 2L * 60 * 1000
    }

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun verifiedPayload(envelope: JSONObject): JSONObject? {
        if (publicKeyDerBase64.isBlank()) return null
        return try {
            val encoded = envelope.getString("lease")
            val signature = decode(envelope.getString("signature"))
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(
                X509EncodedKeySpec(Base64.decode(publicKeyDerBase64, Base64.DEFAULT))
            )
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(publicKey)
            verifier.update(encoded.toByteArray(Charsets.UTF_8))
            if (!verifier.verify(signature)) null
            else JSONObject(String(decode(encoded), Charsets.UTF_8))
        } catch (_: Exception) { null }
    }

    fun saveVerified(envelope: JSONObject, trustedServerNow: Long): Boolean {
        val payload = verifiedPayload(envelope) ?: return false
        val now = System.currentTimeMillis()
        val expiresAt = payload.optLong("expiresAt", 0L)
        val issuedAt = payload.optLong("issuedAt", 0L)
        if (payload.optString("device") != deviceId ||
            expiresAt <= trustedServerNow ||
            issuedAt <= 0L || issuedAt > trustedServerNow + ROLLBACK_TOLERANCE_MS ||
            kotlin.math.abs(now - trustedServerNow) > ROLLBACK_TOLERANCE_MS) return false
        return prefs.edit()
            .putString("signed_lease", envelope.toString())
            .putLong("lease_server_time", trustedServerNow)
            .putLong("lease_wall_time", now)
            .putLong("lease_elapsed_time", SystemClock.elapsedRealtime())
            .commit()
    }

    /** True only during a verified lease's first 24h; offline grace needs
     * an explicit separate state and may never exceed 72h.
     */
    fun canStartWithoutNetwork(): Boolean = allowedOfflineAge(RECHECK_MS)

    fun canContinueDuringOutage(): Boolean = allowedOfflineAge(MAX_OFFLINE_MS)

    private fun allowedOfflineAge(limit: Long): Boolean {
        val envelope = try {
            JSONObject(prefs.getString("signed_lease", null) ?: return false)
        } catch (_: Exception) { return false }
        val payload = verifiedPayload(envelope) ?: return false
        if (payload.optString("device") != deviceId) return false
        val lastElapsed = prefs.getLong("lease_elapsed_time", -1L)
        val currentElapsed = SystemClock.elapsedRealtime()
        // A reboot loses our trustworthy monotonic reference: require online validation.
        if (lastElapsed < 0 || currentElapsed < lastElapsed) return false
        val elapsed = currentElapsed - lastElapsed
        if (elapsed > limit) return false
        val savedWall = prefs.getLong("lease_wall_time", 0L)
        val wallNow = System.currentTimeMillis()
        if (savedWall <= 0 || wallNow + ROLLBACK_TOLERANCE_MS < savedWall + elapsed) return false
        val trustedNow = prefs.getLong("lease_server_time", 0L) + elapsed
        return trustedNow > 0 && trustedNow < payload.optLong("expiresAt", 0L)
    }

    fun clear() {
        prefs.edit().remove("signed_lease").remove("lease_server_time")
            .remove("lease_wall_time").remove("lease_elapsed_time").apply()
    }
}
