# Home TV Next — Development Progress

Updated: 2026-11-04

## Completed
- Created isolated development branch: `new-app-v1-development`.
- Added the V1 product specification.
- Set Android application ID to `com.softppj.myhometv.next`, so this app can install beside V6 with separate app-private data.
- Set app label to `My Home TV Next`.
- Set debug APK output name to `HomeTV-Next-V1-debug.apk`.
- Disabled automatic Actions runs on source pushes while the GitHub artifact-storage quota is exhausted.
- Changed the compile-check workflow to manual-only and removed APK assembly and artifact upload. No source push should consume build/artifact quota.

## Build verification
- GitHub Actions run 41800232280 (commit 3dea3fe): Kotlin compilation passed and APK assembly/file verification passed.
- Artifact upload failed because GitHub Actions artifact storage quota was exhausted; no artifact was created.
- GitHub Actions run 41807459986 (commit 0ab337e): the compile-only Kotlin check passed, including the recent audio, playlist-refresh, large-list navigation, auto-unhide, and refresh/deletion safety changes. No APK was assembled or uploaded.
- The rotating date/time/day overlay was committed after that successful run; this newest clock change still needs a fresh compile-only check. No APK/artifact has been created.

## Latest source changes (not yet compiled or device-tested)
- Upgraded the on-video date/time overlay to rotate through date, time, and day of week one at a time, with a short fade/slide transition, transparent background, and text shadow. Added a separate Show day setting (enabled by default); retained the existing position, size, and 12/24-hour controls. This source change is committed but still needs a fresh compile check and Android TV visual verification.
- Added preferred audio language, per-channel Media3 track memory, language-code normalization, and LibVLC audio-track selection.
- Corrected "Use stream default" so it does not silently fall back to Malayalam.
- Added an optional Automatic Playlist Check & Update setting (default ON); cached playlists open first and background checks only run when enabled.
- Added conditional HTTP playlist requests using ETag / Last-Modified plus a content hash to avoid reparsing unchanged feeds. Manual rebuild bypasses the conditional cache; clearing the playlist cache forces a full download.
- Optimized remote channel surfing with a cached channel-key-to-position index, avoiding a full 10k+ channel scan for every Up/Down press. Failure-recovery navigation preserves the original channel order when skipping a failed entry.
- Added Auto Unhide Working Channels (default OFF); it only restores a manually hidden channel after a real foreground playback-start event, not an HTTP reachability result.
- Protected playback during background refresh by relocating the channel that is actually playing when the refresh completes. Playlist deletion invalidates any in-flight refresh so deleted sources cannot be re-added.
- Made the manual hidden-channel check use a stable channel snapshot and re-resolve results against the current playlist after network checks. This prevents stale channel indexes from targeting the wrong channel if a playlist refresh or deletion occurs during the check; the snapshot grouping is linear rather than rescanning the full playlist for every hidden channel.
- Made channel-surfing cache invalidation immediate when favourites, viewer profiles, channel/group hiding, parental locks, skip settings, or temporary failure-hiding state changes. This avoids the short cache window showing a channel that the user has just hidden, locked, skipped, or restored.
- No APKs or artifacts have been generated for these latest changes.

## Existing implementation inherited from the audited baseline
- Media3 playback and LibVLC fallback.
- Playlist channel indexing and cached channel-surfing sequence.
- XMLTV EPG parsing, guide and programme scheduling.
- Date/time overlay and settings.
- Signed offline licence cache implementation.
- YouTube WebView playback, remote controls, startup audio request and quality preference controls.

## Latest source changes (not yet compiled or device-tested)
- Updated the manual hidden-channel reachability check to process 16 hidden channels per batch, yield briefly between batches, and use shorter 2.5-second connection/read timeouts. Requests remain sequential rather than launching a burst of parallel probes. Results still mean URL reachability only, not verified playback; the user must confirm manual unhide unless actual foreground playback triggers the separate Auto Unhide setting. This change is committed but not yet compile-checked or device-tested.

## Not yet verified or completed
- Re-run the compile-only workflow after the rotating date/time/day overlay change; run 41807459986 predates that clock update.
- Large-playlist stress tests (10k, 20k+).
- Device-level Android TV remote and playback tests.
- Full YouTube native-first resolution with robust WebView fallback; current path uses the WebView watch page for YouTube.
- Automatic hidden-channel checks/unhide policy from the V1 specification.
- Independent licence activation and Worker end-to-end validation for the new package.
- Full UI redesign and regression testing.
- Review/integration of the existing `YouTubeStreamResolver`: it is currently not called by the playback path. Settings explicitly identify YouTube WebView watch-page compatibility mode, so native-first YouTube playback is still pending and must not be described as implemented.
- The manual hidden-channel check is race-safe now, but it remains sequential HTTP reachability checking. It does not prove playback and does not automatically unhide channels; bounded parallel scanning and any automatic policy remain pending.

## Release gate
No APK release/prerelease or final release until the user explicitly approves. Device testing must be reported separately from compilation.

- Investigated compile-check run 41806433311: the three Kotlin errors at `MainActivity.kt` lines 3788, 3796, and 3808 all stemmed from declaring Media3 `Tracks.Group` entries as `TrackGroup`. Updated the entry type to `Tracks.Group`, preserving access to its selected-state and underlying `mediaTrackGroup` for `TrackSelectionOverride`. Fix committed; compile-only rerun still required.

- Added a quiet automatic hidden-channel scan 30 seconds after the startup destination opens. It is enabled by default, can be disabled in Settings, skips safe-startup mode, waits for a foreground/licensed session and available channels, and retries scheduling if startup is not ready. It does not delay first playback or open a modal dialog; reachable results appear as a brief bottom banner for manual review. URL reachability still does not count as proof of playback or trigger automatic unhide. Source commits: `89773648bdd926a929dbe89425330d0c4e104241`, `e49916089e64efb7963dc0d946163e344158a118`, and `d17c6aa879b1a81749641d6a001a664c6837fee2`. Not yet compile-checked or device-tested.

- Added a quiet automatic hidden-channel scan 30 seconds after the startup destination opens. It is enabled by default and can be disabled in Settings. It waits for a foreground, licensed session with channels loaded, skips safe-startup mode, and retries scheduling if the app is not ready. It does not delay first playback or open a modal dialog; reachable results are announced in a brief bottom banner that points users to the existing **Check hidden channels** review action. URL reachability is not proof of playback and never automatically unhides a channel. Source commits: `89773648bdd926a929dbe89425330d0c4e104241`, `e49916089e64efb7963dc0d946163e344158a118`, `d17c6aa879b1a81749641d6a001a664c6837fee2`, and `9609a14fa74846c9107d81f65b2000f5f709e209`. Not yet compile-checked or device-tested.


## YouTube native-first playback update (source committed; compile/device test pending)

- Added a guarded native-first attempt for individual YouTube video URLs only. The app checks for a publicly exposed HLS livestream URL on a background thread; channel and playlist URLs, ordinary VOD videos, and unsupported cases continue through the existing YouTube WebView route.
- Added an 8-second UI deadline. If discovery takes too long or finds no eligible HLS URL, the existing WebView player opens. Late resolver results are ignored using a generation and active-screen check.
- Eligible HLS URLs are sent to Media3 (not the user-selected LibVLC path). Startup timeout, prolonged buffering, synchronous setup failure, or Media3 playback error returns to the existing WebView route once. The failed native URL is not retried in a loop.
- Switching channels and stopping playback guard against stale resolver callbacks; timeout callbacks are removed during playback cleanup. The existing IPTV preview path remains unchanged.
- Source commits: `1b32b938ed5f6e5fb070077725bfc1c5669b87ab` and `0320fe47cc65817195edeb871cb3c8d62250e029`. Source-only; no APK, release, or build artifact was created. Kotlin compile-only check and Android TV playback testing remain pending.
- Limitation: native playback is best-effort and only applies when YouTube publicly exposes an HLS livestream manifest. It does not resolve ordinary YouTube video formats or bypass authentication, CAPTCHA, DRM, or access restrictions.

## Playback date/time overlay default (2026-11-04)
- Enabled the on-video date/time/day overlay by default for a fresh installation.
- Kept the existing six selectable positions (top/bottom × left/center/right), with **Top right** as the default.
- Kept the existing individual date/time/day visibility controls, 12/24-hour format, text-size control, transparent background, and four-second rotation.
- Existing saved user preference is respected; users who previously switched the overlay OFF remain OFF until they enable it.
- Source commit: `f180ed155ff069e0529275f8dee4a2a00801ee31`.
- A fresh compile-only workflow run is required for this commit. Android TV visual verification remains a device test and has not been performed.


## Critical-readiness audit — 2026-11-04

### Important correction to build status
- Workflow run **41815289650** succeeded, but GitHub reports its checked-out branch as `main` and its source SHA as `db3871a109421933f4144ebcf85e9f23e491c078`.
- Therefore this successful run does **not** verify branch `new-app-v1-development` or its latest commit `fb3c22c7181ac04a91d61b149eb8608d7ddf9e5d`. A compile check must be dispatched with `new-app-v1-development` explicitly selected.

### Licence integration finding and source correction
- The Android app already calls `SignedLicenseCache` and rejects unsigned validation responses.
- The repository Worker source still returned only `{ok:true, expiry}` from `/validate`; this could never satisfy the Android signed-lease verifier.
- Updated `cloudflare/hometv-license.js` to sign a device- and token-bound RSA-SHA256 lease using the `LICENSE_SIGNING_PRIVATE_JWK` Worker secret. The response now includes `lease`, `signature`, and `serverTime` in the format expected by `SignedLicenseCache`.
- Worker source commit: `4216a1fb97e0aeaff8dedfa68d27788be79bac7a`.
- Cloudflare confirms that the live `hometv-license` Worker has a secret binding named `LICENSE_SIGNING_PRIVATE_JWK`. With the user's approval, the corrected source was deployed on 2026-11-04. Active deployment `d855ff6c-93c1-454a-a10e-d6a617aa014d`, version `0fb63744-9204-4110-aae5-5bb393aa7054` (100% traffic). Existing bindings and compatibility date were preserved. Cloudflare accepted the module and reports `startup_time_ms: 1`. Public health/activation/validation endpoints could not be exercised from this environment, and no real test licence/device was used. Licence activation/validation remains a release blocker until a test device verifies the signed response against the APK public key.

### Confirmed from source; not device-tested
- Cached playlist startup with background refresh; optional automatic playlist checking; conditional HTTP validation and channel-position index for large-list navigation.
- Media3 and LibVLC playback paths; XMLTV parsing and EPG guide/scheduling; preferred audio language and manual track selection.
- Rotating date/time/day overlay and selectable placement; current compile workflow success was on `main`, not this development branch.
- Hidden-channel scan reports URL reachability. That is not proof of playback. Automatic unhide is not fully verified against the specification's positive-playback requirement.
- YouTube resolver is best-effort for eligible publicly exposed HLS live streams; ordinary YouTube content continues through WebView. This is not universal native YouTube playback.
- The current app has Media3 and LibVLC player paths. The FFmpeg decoder dependency is a Media3 decoder extension, not a separately integrated third player engine.

### Remaining critical gates
1. Deploy the updated Worker source without changing existing secrets/bindings, then verify activation, signed validation, expiry, offline grace, clock rollback, surrender, and cache-clear behaviour end to end.
2. Run the Kotlin compile workflow on `new-app-v1-development` (not `main`), then assemble a debug APK.
3. Add/run automated tests for playlist parsing/indexing, signed licence validation and clock cases, hidden-channel decisions, EPG matching, and YouTube fallback/state handling.
4. Stress-test 10,000 and 20,000+ channel playlists, including initial startup, Up/Down navigation, refresh, and playlist deletion.
5. Test on the actual Android TV: remote navigation/focus, HLS/DASH/SmoothStreaming/MP4/WebTV, original stream quality, engine fallback and no silent URL/engine switching, audio selection, EPG, YouTube, safe startup, and side-by-side installation with V6.
6. Finish visual/regression review of the cinematic Home UI and confirm all carried-forward V6 functions.

No final APK, prerelease, or release is approved or created by this audit.


### Live Cloudflare deployment — 2026-11-04
- User approved deployment of the signed-lease validation correction.
- Deployed `cloudflare/hometv-license.js` to existing Worker `hometv-license` using the content-only endpoint; Worker configuration and bindings were preserved.
- Active deployment: `d855ff6c-93c1-454a-a10e-d6a617aa014d`; version: `0fb63744-9204-4110-aae5-5bb393aa7054`; 100% traffic.
- Preserved bindings: `ADMIN_TOKEN`, `GITHUB_REPO`, `GITHUB_TOKEN`, `LICENSE_PATH`, `LICENSE_SIGNING_PRIVATE_JWK`, `PLAYLIST_URL`; compatibility date remains `2026-10-19`.
- Cloudflare accepted the module upload successfully. End-to-end activation and signed validation still need a real test device/licence. The Android public key/private signing key match has not been independently verified from a test signature, so do not declare licence flow release-ready yet.


### Activation safety correction — 2026-11-04
- Fixed an activation-flow issue in `MainActivity.kt`: a successful `/activate` response followed by a failed or invalid signed `/validate` response could previously still open the app because the returned token alone was treated as success.
- The app now starts only after `SignedLicenseCache.saveVerified()` accepts the signed lease. If activation has bound a token but validation fails, the token is retained for **Retry Existing License**, while `licenseReady` remains false and the app stays on the licence screen.
- Commit: `880b7fbc284fa5e1b1e7154b1c73328e8b4a3231`.
- This is a source correction only. The development-branch Kotlin compile, real Worker activation/validation, and Android TV tests remain pending.


## Current verified status — 2026-11-04 (supersedes earlier status notes above)

- Active development branch: `new-app-v1-development`. The latest reviewed compile-check run is GitHub Actions run **41817687491**, job **126532539266**, source commit `789f30b8a8aa8457598f73557a3b28e2b137e748`. The run checked out the development branch and completed `:app:compileDebugKotlin` successfully.
- This is a Kotlin compile check only. It did **not** assemble an APK, upload an artifact, run automated unit tests, or test the app on Android TV. No APK, prerelease, or release has been created.
- The corrected Worker source was deployed to the live `hometv-license` Worker on 2026-11-04 with user approval. Active deployment: `d855ff6c-93c1-454a-a10e-d6a617aa014d`; version: `0fb63744-9204-4110-aae5-5bb393aa7054`; 100% traffic. Existing bindings and compatibility date were preserved; no licence records were changed.
- The deployed Worker has not yet passed a real activation/validation test using a test licence and Android device. The Cloudflare private signing key cannot be read back, so matching it to the app's embedded public key must be proven by a real signature verification. This remains a release blocker.
- Current Android player engines confirmed in source: Media3 and LibVLC. FFmpeg is a Media3 decoder extension; a separate IJKPlayer engine is **not** integrated.
- YouTube native-first support remains limited to eligible individual public livestreams that expose HLS. Ordinary YouTube videos and unsupported URLs use WebView. The resolver and fallback still need device verification.
- Hidden-channel scans are bounded and race-safe, but URL reachability is not proof of playback. Automatic unhide is only intended after a real foreground playback-start event; this needs device verification.
- Remaining work: automated tests for signed licence validation and playlist/indexing; real Worker/device licence verification; debug APK assembly after test additions; 10k/20k+ playlist stress tests; Android TV remote/playback/EPG/YouTube/overlay tests; full UI and regression review. Keep V6, `main`, and release branches untouched.


## Automated licence tests added — 2026-11-04

- Added `SignedLicenseCacheTest.kt` with generated test-only RSA keys. Coverage includes valid signed lease acceptance, tampered signature rejection, device binding, token-hash binding, already-expired lease rejection, seven-day offline-grace enforcement, wall-clock rollback rejection, and local cache clearing.
- The production verifier still defaults to the pinned Cloudflare public key. A constructor-only key override exists to let tests generate a disposable keypair; the private test key is never used by the app or Worker.
- Added JUnit 4 and Robolectric test dependencies and enabled Android resources for local unit tests.
- Updated the manual-only GitHub Actions compile workflow to run `:app:testDebugUnitTest` after Kotlin compilation. This does not trigger automatically on every push.
- **Not yet executed:** the new test suite has been committed but no GitHub Actions run has executed it. Run the manual workflow from Actions to verify it. No APK assembly or device test has been run.

## Additional unit coverage and debug-test build workflow — 2026-11-04

- Refactored YouTube stream resolver URL-ID parsing and public-player HLS extraction into side-effect-free helpers; network fetching remains in `resolve()` and still uses the existing timeout policy.
- Added Robolectric tests for supported YouTube URL forms, invalid/host-spoofed URLs, HLS extraction only for playable responses, HTTPS-only manifests, and malformed/missing player data.
- Manual-only Actions workflow now runs Kotlin compilation, unit tests, and then assembles/uploads a **debug test APK** for seven days only if earlier steps pass. It does not publish a Release or prerelease and does not run on every push.
- The test suite and updated workflow are committed but have **not yet been executed**. There is no verified test result or APK artifact yet. The workflow must be manually started from the repository Actions page.

## Source review notes

- Large-playlist protections found in source include a 32 MiB playlist read cap, bounded three-thread playlist cache/refresh pools, cached playlist parsing off the UI thread, virtualized ListView rows, and cached Up/Down surfing indexes. These are source-level observations only; no 10k/20k stress test has been run.
- Per-playlist logical channel numbering and optional continuous numbering are present in the indexing code. The runtime numbering and playback navigation still require Android TV verification.
- Hidden-channel reachability scan explicitly warns that an HTTP response does not verify video playback; auto-unhide is tied to the foreground player Playing callback. Runtime behavior still needs device testing.

## Playback settings drawer and picture adjustments — 2026-11-06

- Commit b1371028: playback option dialogs now leave the playback-settings drawer open, so users can change aspect ratio, picture quality, buffering, audio, and other settings consecutively without reopening the drawer. The drawer closes only for Home and Full Settings destinations that replace playback.
- Added a direct Picture adjustments entry to the playback-settings drawer, showing the active channel's selected preset.
- Clarified in the picture-mode dialog that Cinema/Vivid/Natural/Soft are HomeTV-defined starting points, not TV calibration standards, and apply only to the selected channel. Engine Native (Original) adds no HomeTV picture correction.
- Source committed only. No compile check or Android TV runtime test has yet been run for this commit.


## Final pre-release source audit — 2026-11-06

**Branch audited:** `new-app-v1-development` (Home TV Next V1). The V6 branch and release branch were not changed.

### Corrections committed during the audit
- Fixed the Next V1 licence gateway's UAE expiry-date validation. It now validates the calendar date in UTC and separately calculates the UAE (+04:00) expiry boundary.
- Replaced email/share-based diagnostic and settings-backup export with Android Storage Access Framework file pickers. Reports save as TXT; settings save as JSON; restore selects a JSON backup file.
- Excluded licence tokens, device identity, signed offline lease data, parental PIN, and crash-recovery state from manual settings backups. Android automatic backup is disabled. A warning explains that playlist URLs may contain provider credentials.
- Changed the diagnostic viewer to show a bounded preview while saving the complete available 30-day report.
- Improved safe-startup crash counting: Android 11+ uses OS process-exit reasons for Java/native crashes and ANRs; older Android uses the uncaught-exception handler. Ordinary background process eviction is not counted as a crash.
- Wired the connection-loss waiting/retry path into playback failures and moved network detection to Android NetworkCapabilities. Recovery retries the selected channel rather than treating every stream as broken.
- Prevented EPG reminder polling from spinning at zero delay while the app is backgrounded.
- Rebinds the active channel after playlist refresh if its list index changes, keeping player listeners and recovery callbacks aligned.
- Keeps the playback options drawer available after buffer, engine, and alternative-stream changes.
- Standardized menu terminology, removed an inactive duplicate menu item, and clarified the Android 10+ boot-launch restriction.
- Made the compile-check workflow manual-only to conserve Actions quota and updated its GitHub Actions versions.
- Added a required `MASTER_PLAYLIST_URL` repository variable to the manual APK workflow. The APK build stops if it is missing, rather than silently packaging the raw GitHub fallback.

### Automated verification
- Latest audited application source was checked by workflow run [42183183073](https://github.com/SoftPPJ/HomeTV/actions/runs/42183183073), commit `84bbfde1ba519ee774cd56e87e97423a2f42ad10`.
- `:app:compileDebugKotlin` passed; `:app:testDebugUnitTest` passed. The run completed successfully.
- Remaining compiler messages are Android deprecation warnings (system UI flags, `onBackPressed`, and `TRIM_MEMORY_RUNNING_LOW`) plus the Gradle 8.9 lifecycle warning. No Kotlin compilation errors were reported.
- The automated workflow does not assemble an APK, run Android lint, run instrumentation tests, or exercise playback on physical TV hardware.

### Release blockers and items requiring confirmation
- **Licence service:** the app points to `hometv-license-next.prasanthpjoy.workers.dev`. The source gateway requires `UPSTREAM_BASE` and `LICENSE_SIGNING_PRIVATE_JWK`; the live deployment, secret bindings, and match with the public key embedded in the app have not been verified. The expiry fix is committed to source but has not been deployed to Cloudflare.
- **Playlist source:** the app's non-workflow fallback remains the raw GitHub `Master.m3u` URL. The APK workflow now requires the repository variable `MASTER_PLAYLIST_URL`; that variable must be set to the approved Cloudflare Worker playlist URL before an APK build. The exact Worker URL was not available during this audit and was not guessed.
- **YouTube:** `YouTubeStreamResolver` and its unit tests exist, but `MainActivity` currently uses the WebView compatibility path; native-first resolution is not wired into active playback. This differs from the native-first item in the V1 specification and should be explicitly accepted or restored before final approval.
- **Hidden-channel checks:** the background/manual scan checks URL reachability, not successful video playback. Automatic unhide still requires an actual foreground playback success.
- **Performance and device validation:** no 10k+ playlist stress run or physical Android TV test has been completed. Remote navigation, HLS/DASH/TS/MP4/LibVLC playback, picture controls, backup/restore via TV/USB/Drive, licence activation/offline grace, and crash recovery still require device testing.
- **Release packaging:** the current APK workflow builds a debug APK and can publish a prerelease only when explicitly dispatched with its publish option. There is no production signing configuration or final stable release build in this branch. The included FFmpeg decoder is marked GPLv3 in the Gradle configuration; distribution compliance must be checked before distributing the APK.
- `targetSdk` remains 32; review and update it only with Android TV compatibility testing.

**Release decision:** source compilation and unit tests pass, but Home TV Next V1 is not yet approved as a final APK. No APK was assembled or published during this audit.

## Playlist-source decision and follow-up corrections — 2026-11-06

- Owner confirmed that the app should continue using the existing `TV.m3u` feed; do not integrate `Master.m3u`.
- Updated `MainActivity.playlists()` so the default feed is `https://raw.githubusercontent.com/my-hometv/tv/main/TV.m3u`. Existing saved entries pointing to either the old TV feed or the former Master feed are migrated to one `TV` entry, preserving its enabled/disabled state. Other user-configured playlists remain intact.
- Removed the `MASTER_PLAYLIST_URL` BuildConfig injection from `app/build.gradle.kts`.
- Removed the `MASTER_PLAYLIST_URL` repository-variable requirement and validation step from the manual APK workflow. The workflow remains manual-only and still requires the explicit publish input before it will publish a prerelease.
- V6 branch, V6 licensing, and all licensing source/Worker configuration were left untouched in this follow-up.
- YouTube native-first playback was not enabled: the current WebView compatibility route is intentionally retained until its audio/playback behaviour can be tested and approved. The resolver remains present but is not invoked by MainActivity.
- The compile-check workflow remains manual-only. A new compile/test run has not yet been triggered for these latest playlist-source changes; the previous successful run does not validate these new commits.
- Physical Android TV tests, 10k+ playlist stress testing, production signing/release preparation, and release compliance review remain outstanding.


## Source compliance audit and corrections — 2026-11-06

- Extracted M3U parsing into a pure component and added tests for alternate URLs, metadata, headers, deleted groups and 10,000 entries.
- Fixed local `file:` playlist refresh so imported USB/device M3U files are parsed and cached rather than treated as HTTP connections.
- Added a dedicated YouTube home section and a bounded native-first attempt for publicly exposed HLS livestream manifests, with WebView fallback for unsupported, slow or failed attempts.
- Added licence revalidation when validated connectivity returns, plus a visible no-internet activation state. Transient network errors do not revoke a still-valid signed cache.
- Fixed the Next V1 gateway's handling of `unlimited` licences; the signed lease is non-expiring but still subject to the seven-day offline revalidation ceiling.
- Changed automatic skipping of unavailable channels to OFF by default.
- Added tests for hidden-channel decisions, EPG ID/name matching and date/time/day rotation.
- Confirmed from source: separate package/storage, cached startup/background refresh, bounded playlist parsing, indexed/virtualized channel list, per-playlist and optional combined numbering, playlist deletion successor, Media3/LibVLC, XMLTV guide/scheduling, signed lease cache, diagnostics and backup exclusion of licence/PIN.
- Not integrated: an independent third IJK/FFmpeg player engine. The FFmpeg dependency is a Media3 decoder extension; GPLv3 distribution obligations and device compatibility need review before it can be called a supported third engine.
- Native YouTube remains best-effort for public HLS livestream manifests; ordinary videos use WebView.
- Android TV stress/device tests and live Worker/signing-key/activation verification remain outstanding. No Worker deployment, V6 change, licence record, release or prerelease is part of this commit.
- Home navigation is card/row based rather than a persistent left rail; playback overlay navigation exists.


### Additional verification coverage
- Native YouTube quality preferences now apply to Media3 when a public HLS manifest is found; offline startup bypasses the network resolver and uses WebView fallback.
- The native discovery screen shows a short status banner during the bounded lookup.
- Added a signed-cache unit test proving that a far-future non-expiring lease is accepted while the existing seven-day offline grace remains enforced.
- Worker expiry parsing is split into a pure helper with Node unit tests for unlimited, date-based and invalid expiries.
