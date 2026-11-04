# Cached licence validation: security integration status

**Current status: Android verifier integrated; Worker signing source updated but NOT yet deployed or release-enabled.** The Android app verifies an RSA-SHA256 signed lease before allowing cached licence access. The Android cache is limited to seven days and the app requests background revalidation after 24 hours. Cloudflare confirms the `LICENSE_SIGNING_PRIVATE_JWK` secret binding exists on the live `hometv-license` Worker. The repository Worker source now returns the matching signed response (commit `4216a1fb97e0aeaff8dedfa68d27788be79bac7a`), but the live Worker has not yet been updated and end-to-end verification has not passed. Do not distribute an APK until deployment and the required licence tests are complete.

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
