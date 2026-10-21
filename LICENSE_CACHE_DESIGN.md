# Cached licence validation: security integration status

**Current status: preparation only; NOT enabled.** The development branch includes `SignedLicenseCache.kt`, a fail-closed RSA-SHA256 signed-lease verifier, but the live Cloudflare `/validate` endpoint does not issue signed leases. The current application still validates online at launch. Never treat an unsigned successful `/validate` response as proof of offline entitlement.

## Intended contract for a future server update (requires separate approval)
- `/validate` returns a signed, canonical base64url JSON lease and its RSA-SHA256 signature.
- Lease binds a stable licence identifier and the device identifier, with trusted `issuedAt` and `expiresAt` timestamps in milliseconds.
- Android APK includes only the public verification key; the private signing key stays in a secure server secret.
- Server also returns a trusted server timestamp; client must not derive it from the device wall clock.
- A successfully verified lease can be reused for at most 24 hours without network; if revalidation fails because the server is unreachable, an offline grace of at most 72 hours may apply, never beyond signed expiry.
- Clock rollback, device reboot without reliable time continuity, invalid signature, invalid device binding or an explicit revoked/expired response requires immediate online verification or access denial.
- A successful explicit revocation/expiry response clears the cached lease; network errors alone do not clear it.
- Offline revocation cannot be instantaneous. A revoked device could keep using a previously signed lease during the allowed offline window.
- Licence validation does not make protected online playlist delivery work offline. A separate carefully scoped offline playlist policy would be needed, without persisting master secrets.

## Before enabling
1. Agree on canonical lease envelope and test vectors, including server-side signing.
2. Add a real public key to the APK; do not add the signing private key.
3. Integrate the verifier into activation/startup, explicit revocation and surrender.
4. Test clock rollback, reboots, expired lease, wrong device, tampered signature, network outage, revoked licence, and 24h/72h boundaries.
5. Run Android CI and test the resulting APK on a device.

**No Cloudflare Worker, R2 object, existing APK or main branch has been modified.**
