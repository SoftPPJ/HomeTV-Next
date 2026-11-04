package com.softppj.hometv

import android.util.Base64
import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SignedLicenseCacheTest {
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var signingKeys: KeyPair

    private val device = "test-device-identifier-1234"
    private val token = "test-license-token-for-unit-tests"

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        preferences = context.getSharedPreferences("signed-license-tests", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        signingKeys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }

    private fun cache(
        deviceId: String = device,
        licenseToken: String = token
    ) = SignedLicenseCache(
        preferences,
        deviceId,
        licenseToken,
        Base64.encodeToString(signingKeys.public.encoded, Base64.NO_WRAP)
    )

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private data class SignedEnvelope(val json: JSONObject, val serverTime: Long)

    private fun signedEnvelope(
        deviceId: String = device,
        licenseToken: String = token,
        serverTime: Long = System.currentTimeMillis(),
        expiresAt: Long = serverTime + 30L * 24L * 60L * 60L * 1000L
    ): SignedEnvelope {
        val payload = JSONObject()
            .put("version", 1)
            .put("licenseId", "unit-test-license")
            .put("device", deviceId)
            .put("tokenHash", sha256(licenseToken))
            .put("issuedAt", serverTime)
            .put("serverTime", serverTime)
            .put("expiresAt", expiresAt)

        val lease = Base64.encodeToString(
            payload.toString().toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(signingKeys.private)
        signer.update(lease.toByteArray(Charsets.US_ASCII))
        val signature = Base64.encodeToString(
            signer.sign(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return SignedEnvelope(
            JSONObject().put("ok", true).put("lease", lease).put("signature", signature)
                .put("serverTime", serverTime),
            serverTime
        )
    }

    @Test
    fun acceptsValidDeviceBoundSignedLease() {
        val envelope = signedEnvelope()
        assertTrue(cache().saveVerified(envelope.json))
        val status = cache().status()
        assertTrue(status.allowed)
        assertFalse(status.needsRevalidation)
    }

    @Test
    fun rejectsTamperedSignature() {
        val envelope = signedEnvelope().json
        envelope.put("signature", "invalid-signature")
        assertFalse(cache().saveVerified(envelope))
    }

    @Test
    fun rejectsLeaseForDifferentDevice() {
        val envelope = signedEnvelope(deviceId = "another-device-identifier-5678")
        assertFalse(cache().saveVerified(envelope.json))
    }

    @Test
    fun rejectsLeaseForDifferentToken() {
        val envelope = signedEnvelope(licenseToken = "different-license-token")
        assertFalse(cache().saveVerified(envelope.json))
    }

    @Test
    fun rejectsAlreadyExpiredLease() {
        val now = System.currentTimeMillis()
        val envelope = signedEnvelope(serverTime = now, expiresAt = now)
        assertFalse(cache().saveVerified(envelope.json))
    }

    @Test
    fun deniesCacheAfterSevenDayOfflineGrace() {
        val envelope = signedEnvelope()
        assertTrue(cache().saveVerified(envelope.json))
        val age = SignedLicenseCache.MAX_OFFLINE_MS + 5_000L
        val now = System.currentTimeMillis()
        preferences.edit()
            .putLong("signed_license_wall_time", now - age)
            .putLong("signed_license_server_time", envelope.serverTime - age)
            .putLong("signed_license_elapsed_time", 0L)
            .putLong("signed_license_last_seen_wall", now - age)
            .commit()

        val status = cache().status()
        assertFalse(status.allowed)
        assertTrue(status.reason.contains("offline grace"))
    }

    @Test
    fun deniesCacheAfterWallClockRollback() {
        val envelope = signedEnvelope()
        assertTrue(cache().saveVerified(envelope.json))
        preferences.edit()
            .putLong("signed_license_last_seen_wall", System.currentTimeMillis() + 10L * 60L * 1000L)
            .commit()

        val status = cache().status()
        assertFalse(status.allowed)
        assertTrue(status.reason.contains("backwards"))
    }

    @Test
    fun clearRemovesCachedLeaseWithoutChangingAnyServerState() {
        val envelope = signedEnvelope()
        assertTrue(cache().saveVerified(envelope.json))
        cache().clear()
        assertFalse(cache().status().allowed)
        assertTrue(cache().status().reason.contains("No signed lease"))
    }
}
