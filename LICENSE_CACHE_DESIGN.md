# Cached licence validation: security integration status

**Current status: Android verifier integrated; Worker signing source updated but NOT yet deployed or release-enabled.** The Android app verifies an RSA-SHA256 signed lease before allowing cached licence access. The Android cache is limited to seven days and the app requests background revalidation after 24 hours. Cloudflare confirms the `LICENSE_SIGNING_PRIVATE_JWK` secret binding exists on the live `hometv-license` Worker. The repository Worker source returns the signed response (commit `4216a1fb97e0aeaff8dedfa68d27788be79bac7a`). With user approval, it was deployed to the live `hometv-license` Worker on 2026-11-04 as version `0fb63744-9204-4110-aae5-5bb393aa7054` (100% traffic). Existing bindings and compatibility date were preserved. End-to-end verification against a real test licence/device has not yet passed; the APK public key/private Worker signing key pair must still be verified with a real signed response. Do not distribute an APK until deployment and the required licence tests are complete.

## Signed response contract

The Worker returns the existing `ok` and `expiry` fields plus:

- `lease`: base64url-encoded UTF-8 JSON.
- `signature`: base64url RSA-SHA256 signature over the exact ASCII `lease` value.
- `serverTime`: informational response field; the authoritative time is also inside the signed lease.

The signed JSON contains `version`, `licenseId`, `device`, `tokenHash`, `issuedAt`, `serverTime`, and `expiresAt` (Unix epoch milliseconds). The token hash is SHA-256 of the device token. The lease is bound to both the device installation ID and its token.

## Client rules

- The APK contains only the public SPKI verification key. The matching private key must remain in the Cloudflare Worker secret and must never be committed to GitHub or embedded in the APK.
- The app verifies the signature, device binding, token binding, issue time and expiry before saving or using a lease.
- A verified lease can be used offline for at most seven days, and never beyond its signed expiry.
- The app checks the signed cache locally at startup. It does not contact the Worker on every launch; it attempts background revalidation after 24 hours.
- Signed server time is advanced using the device wall clock and Android monotonic elapsed time. A detected clock rollback fails closed and requires online validation. After a reboot, the saved wall-clock anchor is used; a clock rollback is still rejected.
- A definitive server rejection (expired, revoked, or inactive token) clears the cached lease. A temporary network/service failure does not reset or extend the seven-day period.
- Surrender clears the local signed lease only after the Worker confirms device release.
- The lease is stored in app-private preferences, not the Android cache directory. Clearing the app cache therefore does not release the device or erase the licence state.

## Security and operational limitations

- Offline revocation cannot be instantaneous. A device that was revoked while offline may continue until the signed lease expires or the seven-day offline limit is reached.
- A user who deliberately advances the device clock can cause early expiry. Rollback detection prevents the ordinary clock-reset bypass, but no client-only offline scheme can provide tamper-proof time on a fully compromised/rooted device.
- Licence validity does not make the protected playlist available offline. The playlist endpoint still requires a live Worker validation and upstream access.
- Do not use an unsigned `/validate` response as proof of offline entitlement.

## Required tests before release

1. Valid signed lease, tampered lease, tampered signature, wrong device and wrong token.
2. Date-only expiry at the exact UTC end-of-day boundary and unlimited expiry.
3. Clock rollback, clock advance, reboot, and seven-day boundary.
4. Network unavailable at startup before 24 hours, after 24 hours, and after seven days.
5. Server-side revoke/expire while online and while offline.
6. Activation, retry, device surrender, app cache clear, and full app-data clear.
7. Verify the deployed Worker returns a valid signed envelope and the APK contains only the public key.

**Release gate:** do not issue a new APK until the Cloudflare `/validate` implementation is deployed, an end-to-end signature test passes, and Android compilation succeeds.


## Deployment and verification update — 2026-11-04

The statement above that the Worker is not deployed is superseded. With user approval, the signed-lease Worker source was deployed to the live `hometv-license` Worker on 2026-11-04.

- Deployment ID: `d855ff6c-93c1-454a-a10e-d6a617aa014d`
- Active version ID: `0fb63744-9204-4110-aae5-5bb393aa7054`
- Traffic allocation: 100%
- Existing Worker bindings and compatibility date were preserved; no licence records were modified.
- GitHub Actions compile check 41817687491 passed on `new-app-v1-development` (commit `789f30b8a8aa8457598f73557a3b28e2b137e748`). This is a Kotlin compile check, not APK assembly or Android TV testing.

**Still unverified and release-blocking:** no real test licence/device has exercised `/activate` followed by `/validate`; the signed response has not yet been verified against the public key embedded in the Android app. The private signing key is intentionally not retrievable. A real signed response must verify successfully before the licence integration is considered complete.


## Automated test implementation update — 2026-11-04

A test-only public-key injection parameter was added to `SignedLicenseCache`; production defaults to the fixed embedded Cloudflare public key. Tests generate their own temporary RSA key pair and do not access or expose the production private key.

Added Robolectric/JUnit tests for valid signatures, tampered signatures, device binding, token binding, expiry, seven-day offline grace, clock rollback, and cache clearing. The manual GitHub Actions compile workflow now runs `:app:testDebugUnitTest` after Kotlin compilation.

These tests are **committed but not yet run**. They validate the Android cache verifier with generated test keys; they do not prove that the deployed Cloudflare Worker's secret matches the app's production public key. That still requires a real signed response from the deployed Worker.


## Test coverage update — 2026-11-04

The Android test suite now also covers YouTube URL ID parsing and HLS player-response extraction. It checks supported URL patterns, rejects spoofed hosts and malformed IDs, requires `playabilityStatus=OK`, accepts only HTTPS HLS manifests, and rejects malformed or missing metadata.

The manual Actions workflow now assembles and uploads a short-retention debug test APK only after Kotlin compilation and unit tests pass. No release/publish step is included. These tests and the workflow have not yet been executed.
