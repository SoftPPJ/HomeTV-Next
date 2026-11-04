# Home TV Next — Development Progress

Updated: 2026-11-04

## Completed
- Created isolated development branch: `new-app-v1-development`.
- Added the V1 product specification.
- Set Android application ID to `com.softppj.myhometv.next`, so this app can install beside V6 with separate app-private data.
- Set app label to `My Home TV Next`.
- Set debug APK output name to `HomeTV-Next-V1-debug.apk`.
- Replaced the inherited V6 release workflow with an artifact-only test build workflow. No release or prerelease is created.
- Added a branch push compile/build check and APK artifact upload.

## Build verification
- GitHub Actions run 41799402590: Kotlin compilation passed.
- APK assembly passed.
- APK file verification passed.
- That run did not yet include the artifact-upload step; the workflow was then updated to upload the APK on the next branch-triggered run.

## Existing implementation inherited from the audited baseline
- Media3 playback and LibVLC fallback.
- Playlist channel indexing and cached channel-surfing sequence.
- XMLTV EPG parsing, guide and programme scheduling.
- Date/time overlay and settings.
- Signed offline licence cache implementation.
- YouTube WebView playback, remote controls, startup audio request and quality preference controls.

## Not yet verified or completed
- Large-playlist stress tests (10k, 20k+).
- Device-level Android TV remote and playback tests.
- Full YouTube native-first resolution with robust WebView fallback; current path uses the WebView watch page for YouTube.
- Automatic hidden-channel checks/unhide policy from the V1 specification.
- Independent licence activation and Worker end-to-end validation for the new package.
- Full UI redesign and regression testing.

## Release gate
No APK release/prerelease or final release until the user explicitly approves. Device testing must be reported separately from compilation.
