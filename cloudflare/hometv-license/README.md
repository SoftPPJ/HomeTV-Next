# HomeTV licence Worker

The deployed Worker is `hometv-license`. Its existing GitHub-backed licence records, admin endpoints, device release flow and protected playlist endpoint are retained.

## Signed offline lease

- `/validate` signs a short JSON lease with RSA-SHA256.
- The private JWK is held only in the Cloudflare Worker secret `LICENSE_SIGNING_PRIVATE_JWK`.
- The public SPKI key is embedded in `SignedLicenseCache.kt`; it is safe to ship in the APK.
- The signed claims bind the lease to the installation ID and SHA-256 hash of the device token, and include server issue time and expiry.
- The Android app accepts the verified cache for at most seven days, never beyond the signed expiry. It attempts background revalidation after 24 hours rather than contacting the Worker on every launch.
- A definitive server rejection invalidates the cache. A temporary network failure does not extend the signed lease.

Do not commit the private JWK or any other Worker secrets. Update the Worker module content without replacing its existing bindings.
