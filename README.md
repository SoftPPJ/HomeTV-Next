# My Home TV Next — V1

A separate Android TV / Android IPTV player project being developed independently from My Home TV V6.

## Repository and branch

- Repository: [SoftPPJ/HomeTV-Next](https://github.com/SoftPPJ/HomeTV-Next)
- Active development branch: `new-app-v1-development`
- Default branch: `main` (kept separate from the development branch for now)
- Android application ID: `com.softppj.myhometv.next`
- App label: `My Home TV Next`
- Debug APK filename configured by the project: `HomeTV-Next-V1-debug.apk`

The distinct application ID is intended to allow Next V1 to be installed alongside V6, with separate app-private data.

## Development direction

The V1 specification focuses on:

- Fast startup using the last valid cached playlist and last watched channel, with playlist refresh in the background.
- Efficient parsing, indexing and navigation for large playlists, including 10,000+ channel lists.
- Multiple playlist management, per-playlist channel numbering, and safe playlist deletion/refresh.
- Media3 as the primary playback engine and LibVLC fallback, with confirmed URL/engine choices preserved.
- Signed, device-bound licence caching with the specified offline-grace and expiry safeguards.
- XMLTV EPG, programme information and scheduling.
- Date/time/day overlay controls.
- YouTube playback with a guarded native HLS attempt for eligible live streams and WebView fallback. This is not universal native playback for all YouTube content.
- Remote-first Android TV navigation, diagnostics and safe-start behaviour.

These are development goals and source-level capabilities, not a statement that every feature has passed testing.

## Build and test workflows

- **MyTV V1 compile and test only**: manually dispatched Kotlin compilation and unit tests.
- **HomeTV Next - build and publish prerelease APK**: a separate manually dispatched workflow. Its `publish_apk` input defaults to `false`; do not enable publishing without explicit project-owner approval.

Choose the `new-app-v1-development` branch when checking or building the V1 development source. A successful compile does not establish Android TV runtime compatibility.

## Licensing

The V1 source includes a separate signing gateway under `cloudflare/hometv-license-next/`. It delegates licence operations to the existing licensing backend and signs validation leases for the V1 app. Its private signing key and upstream configuration must remain Cloudflare secrets; never commit private keys, licence tokens, administrator credentials or protected playlist URLs.

Do not deploy or change the live Worker solely because source files are present. Verify configuration and obtain approval before deployment.

## Current verification status

- The development branch was imported from `SoftPPJ/HomeTV` with its commit history preserved.
- Compilation, APK packaging, licence end-to-end validation, large-playlist stress testing and Android TV device testing must be checked against the latest development commit.
- YouTube native playback is best-effort for eligible publicly exposed HLS live streams; WebView remains the fallback and the route for unsupported content.
- No device test should be marked passed without observed results.

## Release policy

No production APK, prerelease or release should be published without explicit approval. Keep the original private `SoftPPJ/HomeTV` repository unchanged as a backup until both new repositories have been fully reviewed.

## Related project

- [HomeTV V6](https://github.com/SoftPPJ/HomeTV-V6)
- [HomeTV APK build repository](https://github.com/ppj1981/HomeTV-Build)
