package com.softppj.hometv

import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlin.math.abs

/**
 * Offline licence cache. Only an RSA-SHA256 lease issued by the HomeTV Worker
 * can grant cached access. The signing private key never ships in the APK.
 */
internal class SignedLicenseCache(
    private val prefs: SharedPreferences,
    private val deviceId: String,
    private val token: String,
    // Injectable only for deterministic cryptographic tests; production uses the pinned Worker key.
    private val publicKeyDerBase64: String = PUBLIC_KEY_DER_BASE64
) {
    data class Status(
        val allowed: Boolean,
        val needsRevalidation: Boolean,
        val ageMillis: Long,
        val reason: String
    )

    companion object {
        const val REVALIDATE_AFTER_MS = 24L * 60L * 60L * 1000L
        const val MAX_OFFLINE_MS = 7L * 24L * 60L * 60L * 1000L
        private const val CLOCK_TOLERANCE_MS = 2L * 60L * 1000L

        // Public key only. The matching private key is stored as a Cloudflare Worker secret.
        const val PUBLIC_KEY_DER_BASE64 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1E3oG5IbBFm08JsZ/PJMITel6lXGh12HpoALy3aRyaPDBgf8qiHBsMmavZOZX+C9azWEzeVcbRx5JOaCDQ7ueGViS+CmLnBrajsGUWmuNneBgv3e3YyFY1diF/N9Fj11XzVFUrFqMEJncEWHueDijt6xW1ZUIol9TrVPrtnImh0lLS9d8yP9W+nUaF/Wx1BxwA8tlb6DZ3Otc4mFKPIQM1XyEdWKlD+x1oeH2deDRmcbj9s1EfpmRQIkWst8zdmGTKhnT7zjSm0AaPGUcVSQzaN2+hFkcJK7d0wxlMiwOWI8J8us38Zc1F3ut/clTYxBrrUNtsm4NurGakrzhQNcfwIDAQAB"

        private const val KEY_LEASE = "signed_license_lease"
        private const val KEY_SERVER_TIME = "signed_license_server_time"
        private const val KEY_WALL_TIME = "signed_license_wall_time"
        private const val KEY_ELAPSED_TIME = "signed_license_elapsed_time"
        private const val KEY_LAST_SEEN_WALL = "signed_license_last_seen_wall"
    }

    private fun decodeUrl(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun tokenHash(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun verifiedPayload(envelope: JSONObject): JSONObject? {
        return try {
            val encodedLease = envelope.getString("lease")
            val signatureBytes = decodeUrl(envelope.getString("signature"))
            val publicKeyBytes = Base64.decode(publicKeyDerBase64, Base64.DEFAULT)
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(
                X509EncodedKeySpec(publicKeyBytes)
            )
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(publicKey)
            verifier.update(encodedLease.toByteArray(Charsets.US_ASCII))
            if (!verifier.verify(signatureBytes)) return null
            JSONObject(String(decodeUrl(encodedLease), Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    /** Saves only a valid, signed lease for this exact device and licence token. */
    fun saveVerified(envelope: JSONObject): Boolean {
        val payload = verifiedPayload(envelope) ?: return false
        val nowWall = System.currentTimeMillis()
        val serverTime = payload.optLong("serverTime", 0L)
        val issuedAt = payload.optLong("issuedAt", 0L)
        val expiresAt = payload.optLong("expiresAt", 0L)

        if (payload.optInt("version", 0) != 1 ||
            payload.optString("device") != deviceId ||
            payload.optString("tokenHash") != tokenHash() ||
            serverTime <= 0L ||
            issuedAt <= 0L ||
            issuedAt > serverTime + CLOCK_TOLERANCE_MS ||
            expiresAt <= serverTime ||
            abs(nowWall - serverTime) > 24L * 60L * 60L * 1000L
        ) return false

        return prefs.edit()
            .putString(KEY_LEASE, envelope.toString())
            .putLong(KEY_SERVER_TIME, serverTime)
            .putLong(KEY_WALL_TIME, nowWall)
            .putLong(KEY_ELAPSED_TIME, SystemClock.elapsedRealtime())
            .putLong(KEY_LAST_SEEN_WALL, nowWall)
            .commit()
    }

    /**
     * A verified lease may be reused for up to seven days. The signed server
     * time is advanced using both wall-clock and monotonic time. A detected
     * wall-clock rollback fails closed and requires an online validation.
     */
    fun status(): Status {
        val envelope = try {
            JSONObject(prefs.getString(KEY_LEASE, null) ?: return Status(false, true, 0L, "No signed lease"))
        } catch (_: Exception) {
            return Status(false, true, 0L, "Malformed cached lease")
        }
        val payload = verifiedPayload(envelope)
            ?: return Status(false, true, 0L, "Signature verification failed")

        if (payload.optString("device") != deviceId || payload.optString("tokenHash") != tokenHash()) {
            return Status(false, true, 0L, "Lease belongs to another device or token")
        }

        val nowWall = System.currentTimeMillis()
        val lastSeenWall = prefs.getLong(KEY_LAST_SEEN_WALL, 0L)
        val anchorWall = prefs.getLong(KEY_WALL_TIME, 0L)
        val anchorServer = prefs.getLong(KEY_SERVER_TIME, 0L)
        val anchorElapsed = prefs.getLong(KEY_ELAPSED_TIME, -1L)

        if (anchorWall <= 0L || anchorServer <= 0L ||
            (lastSeenWall > 0L && nowWall + CLOCK_TOLERANCE_MS < lastSeenWall) ||
            nowWall + CLOCK_TOLERANCE_MS < anchorWall
        ) return Status(false, true, 0L, "Device clock moved backwards")

        val wallAge = (nowWall - anchorWall).coerceAtLeast(0L)
        val elapsedNow = SystemClock.elapsedRealtime()
        val elapsedAge = if (anchorElapsed >= 0L && elapsedNow >= anchorElapsed) {
            elapsedNow - anchorElapsed
        } else {
            // Device reboot: use the persisted wall-clock anchor, with rollback checks above.
            wallAge
        }
        val age = maxOf(wallAge, elapsedAge)
        val trustedNow = anchorServer + age
        val expiresAt = payload.optLong("expiresAt", 0L)

        if (expiresAt <= 0L || trustedNow >= expiresAt) {
            return Status(false, true, age, "Signed licence has expired")
        }
        if (age > MAX_OFFLINE_MS) {
            return Status(false, true, age, "Seven-day offline grace has elapsed")
        }

        prefs.edit().putLong(KEY_LAST_SEEN_WALL, maxOf(lastSeenWall, nowWall)).apply()
        return Status(true, age >= REVALIDATE_AFTER_MS, age, "")
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_LEASE)
            .remove(KEY_SERVER_TIME)
            .remove(KEY_WALL_TIME)
            .remove(KEY_ELAPSED_TIME)
            .remove(KEY_LAST_SEEN_WALL)
            .apply()
    }
}
