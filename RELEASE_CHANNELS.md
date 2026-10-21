# HomeTV release channels

HomeTV maintains two separate release channels.

## Stable
- The tested APK currently stored in Cloudflare R2 as `hometv-downloads/HomeTV.apk` is the stable release.
- Do not overwrite it from GitHub Actions or from development builds.
- The existing Cloudflare download Worker remains the stable public download URL.
- Promotion to stable requires successful CI build, installation and playback testing on an Android TV, licence activation/validation and EPG verification, plus an explicit manual approval.

## Beta
- Beta APKs are built from `feature/hometv-reliability-updates` or future release-candidate branches.
- Beta builds must be stored as distinct artifacts, e.g. `HomeTV-beta.apk`, and must not upload to `HomeTV.apk`.
- Build verification alone does not qualify an APK for stable release.
- Keep version code and version name distinct across stable and beta releases before installing side by side or upgrading.

## Automatic updates (planned)
- The app should consult a separately hosted signed/versioned JSON release manifest.
- Default update channel: stable; beta is explicit opt-in.
- Never automatically install an APK without Android's installer consent.
- Validate versionCode, package name, APK integrity and HTTPS download URL.
- Do not silently replace the R2 stable object.

## Verification checklist
- Gradle `assembleDebug` / release build succeeds.
- Licence activation, validation, expiry and surrender still work.
- Playback tested using Media3 and LibVLC with fallback.
- Playlist rebuild and background probes work without blocking playback.
- EPG scheduling and remote-control navigation tested.
- Stable Cloudflare APK remains unchanged.
