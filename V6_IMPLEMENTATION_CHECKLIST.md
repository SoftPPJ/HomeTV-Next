# My Home TV V6 — implementation and release gates

Status: IN PROGRESS. This document is a checklist, not evidence of implementation.
Source base: my-home-tv-web. Development branch: my-home-tv-v6-development.
Build V6 only after feature review, compilation and regression tests.

## Baseline audit
- Existing build uses Media3 1.5.1, LibVLC 3.5.1, and Jellyfin FFmpeg audio decoder. No IJKPlayer dependency in inspected Gradle file.
- App currently concentrates substantial behavior in MainActivity.kt; changes must avoid regressions and minimize memory.
- Existing build workflow targets my-home-tv-web, so V6 development branch must not be silently built as V5.

## Implementation checklist (all initially pending until verified)
- [ ] Premium Cinematic Home, low-memory graphics, responsive remote focus
- [ ] Normal IPTV playback regression and Media3/LibVLC fallback, cancellation of stale loads
- [ ] YouTube embedded playback, remote keys, muted-start/unmute and no overlapping controls
- [ ] BhoomTV page resolution, playable stream extraction or clear unsupported fallback
- [ ] Playback session ownership, watchdog, buffering recovery, network handling
- [ ] Channel numbering, numeric input, OK channel info overlay, BACK exit prompt
- [ ] Context-sensitive floating playback settings; aspect preview OK commit/BACK rollback
- [ ] Video quality, audio/subtitles, delay, per-channel persistence and source selection
- [ ] Playlist caching, merging, virtualized channel lists, safe refresh, logical counts
- [ ] EPG timeline, reminders, time-zone handling, per-playlist offset
- [ ] Parental PIN channels/groups and safe storage, distinct from license
- [ ] Viewer profiles with shared playlist storage and separate history/favorites
- [ ] On-demand network speed test and bounded diagnostics
- [ ] Device capability diagnostics and adaptive cache/rendering limits
- [ ] Verify Media3 + LibVLC engine routing, RTMP/VLC handling, and fallback on supported Android TV devices
- [ ] Surya Comedy picture-quality comparison (same URL): variant, resolution, bitrate, deinterlacing, scaling, dropped frames, memory
- [ ] Backup/restore and licensing regression
- [ ] Movie/series resume and next-episode behaviors
- [ ] Test on Android TV with 1k/5k/10k channel playlists, rapid switching, long playback, HOME/BACK
- [ ] Compile, inspect APK and confirm version name V6 and independent app ID

## Release rule
Do not trigger V6 workflow or claim full feature completion until all release-critical features pass. Defer optional features explicitly if tests show unacceptable memory, security, maintenance or stability costs. In particular, IJKPlayer must not be shipped merely because another APK bundles it.

## Implementation activity — 27 October 2026
- Commit cc1d4b19: viewer profiles with separate Favorites, recent channels, movie/series progress key, and configurable EPG offsets.
- Commit af523b2f: on-demand bounded Cloudflare download speed estimate, with connection timeouts.
- Commit fe96dc7a: corrected per-playlist EPG offset mapping to the parsed channel source name.
- These are source changes only. No Gradle compilation, device tests, or release acceptance has occurred. Do not mark corresponding checklist boxes as verified yet.

## Verified build milestone — 27 October 2026
- GitHub Actions run 40474655131: Gradle 8.9 `app:compileDebugKotlin` passed, 14 tasks executed. This verifies Kotlin compilation only, not APK packaging or TV runtime behavior.
- Commit ab60b479: playback lifecycle HOME suspend/resume and BACK exit prompt; unverified on device.
- Commit 4524e84b: resume matches exact stream identity before URL fallback and routes through parental PIN enforcement; compilation and TV regression pending.
- Release-critical features above remain unchecked until individually verified. Do not interpret source presence as feature acceptance.

## V6 packaging preparation — 27 October 2026
- Commit 421c0d2: changed development application ID to `com.softppj.myhometv.v6`, version code to 6, version name to `6.0.0-dev`, and intended APK filename to `HomeTV-V6.apk`.
- This is a source configuration change only; Gradle packaging, install compatibility, licensing migration, and TV runtime remain unverified.
- Next release-critical tasks: inspect playback code, verify Media3/LibVLC fallback, evaluate IJKPlayer without bundling prematurely, and run APK packaging plus device regressions.

## V6 implementation and build update — 27 October 2026
- Commit 420f255: guard queued alternative-stream switches with playback session generation and screen identity.
- Commit b020da5: V6 development push workflow now compiles Kotlin, assembles a debug test APK, and uploads a 14-day HomeTV-V6-debug-test artifact. A successful build has not yet been confirmed.
- Do not mark any of the 20 release gates verified on source changes alone. A test APK is not the final release.
- Required next steps: inspect current Actions result and resolve errors; complete feature-by-feature regression testing (playback, YouTube, large playlists, controls, EPG, PIN/licensing, profiles, diagnostics), test Android TV devices, and decide whether IJKPlayer is warranted.

## Release verification update — 27 October 2026
- GitHub Actions run 40490592071 / job 122143247463 passed Kotlin compilation and debug APK assembly. Produced HomeTV-V6.apk (~191 MB) on the runner; no downloadable artifact upload is configured. This does **not** verify playback or Android TV behavior.
- IJKPlayer 0.8.8 dependencies resolved after commit 3982ab0 using a scoped JCenter mirror. Security, long-term availability, license compliance, runtime ABI and device compatibility remain unverified.
- Commit 073c5e2 adds IJKPlayer video-surface aspect sizing and video-size callbacks. Requires new compilation and device testing. SurfaceView sizing is not equivalent to hardware-decoder crop/stretch validation.

### Required regression matrix — do not mark passed without observed results
| Area | Verification | Result |
| --- | --- | --- |
| Build | Kotlin compile + APK assembly after latest commit | PENDING |
| YouTube | Live video, automatic unmute, no remote-control overlap | PENDING DEVICE |
| Media3 | HLS, MPEG-TS, RTSP if supported, normal audio/video | PENDING DEVICE |
| LibVLC | Fallback on Media3 failure, remember confirmed URL+engine | PENDING DEVICE |
| Two-engine routing | Media3 default, LibVLC RTMP/VLC priority, remembered engine, legacy IJK preference migration | PENDING DEVICE |
| Licensing | New activation, expired token, surrender, offline/invalid response | PENDING INTEGRATION |
| Playlists | 1k/5k/10k import, logical counts, memory, rapid channel switching | PENDING DEVICE |
| Remote | DPAD, OK, BACK, HOME, aspect preview rollback | PENDING DEVICE |
| EPG | Programme reminder, timezone offset, automatic tune, PIN | PENDING DEVICE |
| Persistence | Favorites, profiles, manual URL/engine, backup/restore | PENDING DEVICE |

Release rule: do not describe the app as fully implemented or production-ready until these gates pass. Avoid uploading or publishing an APK without explicit approval.

## Remote channel preview update — 1 November 2026
- Commit 4a4208d: UP/DOWN now move a channel highlight/preview only; the currently playing stream is not replaced.
- OK confirms and plays the highlighted channel. BACK cancels the preview and leaves the current stream playing.
- CH+/CH− remain immediate channel-switch controls and clear any pending preview.
- The preview remains visible until confirmed, cancelled, or another channel is previewed. Drawer/menu navigation is unchanged.
- Source change only. Kotlin compilation and Android TV remote/device regression tests are still pending; do not mark the remote release gate passed yet.


## Engine, startup and memory cleanup update — 1 November 2026
- Commit 56722f2: removed IJKPlayer foreground playback, background probing, engine-picker option, volume hooks, and native player cleanup code. Media3 remains the normal engine; LibVLC remains first for RTMP/VLC-only protocols and the fallback for other streams.
- Removed the three IJKPlayer Gradle dependencies. Old saved IJK engine selections are migrated to Auto; saved IJK-confirmed engine/URL pairs are cleared so the app can establish a supported engine again. The IJK-only JCenter mirror/filter was also removed from settings.gradle.kts.
- Startup: cached playlists are now parsed concurrently with a bounded three-thread pool; resuming a valid saved channel no longer builds/sorts the full surfing list first; ambiguous HTTP URL routing is cached across app restarts for 24 hours and first-time route checks use shorter timeouts.
- Exit/memory: final Activity destruction clears in-memory poster/route/player-session caches and pending main-thread callbacks; Android memory-pressure callbacks evict poster bitmaps. Persistent playlist files, preferences and validated licence cache are intentionally retained to keep startup fast and offline licensing safe.
- Source and dependency changes only. Kotlin compilation, APK assembly, and Android TV playback/startup/exit tests have NOT yet been run. Do not mark release gates passed or publish a prerelease.


## Feature-completion branch — 1 November 2026
- Branch: `v6-feature-completion`, based on `fix/v6-stability-corrections`.
- Commit 09be520: visible two-digit numbering added to the left-side navigation menu and playlist selector. Group and playlist entries continue the numbering sequence. Existing channel-list numbering remains per playlist unless the continuous-number preference is enabled.
- Remote playback key audit: `KEYCODE_CHANNEL_UP` / `KEYCODE_CHANNEL_DOWN` (plus media-next/previous aliases) perform immediate channel changes. DPAD Up/Down currently preview a channel; OK confirms the preview and BACK cancels it. This is not the same as immediate CH+/CH− surfing and needs a device test against the user's remote. No claim of device verification.
- Still pending: cinematic home redesign, full numbered settings/menu audit, 10k+ playlist stability, playlist lifecycle/numbering regressions, backup/restore, licence offline verification, playback/EPG/YouTube regression and Android TV install/device tests.
- This branch is work in progress. No release or APK publication is authorized by this change.


## Large-playlist performance update — 1 November 2026
- Commit 6e58368: channel collection rows now generate display text lazily as ListView requests/recycles visible rows, instead of allocating a second full list of channel-name strings. This reduces peak memory when opening 10k+ channel lists.
- Remote channel surfing now reuses a short-lived (900 ms) filtered/sorted channel-index snapshot during rapid DPAD/CH key repeats, avoiding a full filter/sort/allocation for every repeat event. The cache is invalidated when the channel index is rebuilt and keyed by playlist/favorites mode.
- This is a targeted memory/CPU reduction, not proof that 10k+ playlists are crash-free. Large-playlist import, navigation, playback and device memory tests remain mandatory.

## Playlist deletion consistency update — 1 November 2026
- Commits ab55641 / eb6300a: Playlist Manager and the in-player playlist drawer now use one deletion path. Deleting from the drawer removes the playlist's channels from memory immediately, clears its local lock/history state and cache when no other playlist shares the URL, and updates the selected playlist.
- If the deleted playlist was playing, playback moves to the next enabled playlist's saved last channel, or its first live channel; if neither exists, Home opens. This source change still requires compile and Android TV tests.


## Independent stabilization work — 4 November 2026

- Created `v6-stabilization-integration` from PR #5. Commit comparison confirms PR #4 is already an ancestor of PR #5; both drafts are preserved without merging either into the development branch.
- Added seven-day signed-lease verification and startup integration. The app checks the signed cache locally and attempts online revalidation after 24 hours, rather than contacting the Worker on every launch. The lease is bound to the installation ID and token hash; expiry and clock rollback are checked.
- Added the updated Worker source and deployment notes under `cloudflare/hometv-license/`. The deployed `/validate` endpoint still needs updating and end-to-end verification before the new app can activate.
- No APK build, release, prerelease, or PR merge has been performed. Compilation and all runtime/device gates remain pending.
- Android TV playback, picture quality and remote-control testing are assigned to the user; these gates will remain pending until results are reported.

**Release status: NOT READY.** Source presence is not evidence of successful compilation or device acceptance.


## HomeTV-Next engine modernization — 7 November 2026

- Updated AndroidX Media3 modules to 1.11.1, LibVLC to 3.7.7, the Media3 FFmpeg decoder extension to NextLib 1.11.1-0.17.0 (aligned with Media3 1.11.1), Android Gradle Plugin to 8.13.2, Kotlin to 2.2.10, Gradle to 8.13 and compile SDK to 36.
- Media3 remains primary. LibVLC remains the second engine and the first choice for RTMP/VLC-only protocols. FFmpeg is a Media3 decoder extension only; IJKPlayer is not included.
- LibVLC Auto/Best Available now request the highest adaptive rendition without a resolution cap. Only explicit user quality presets impose a height ceiling. Automatic hardware decoding is enabled and no stream re-encoding is introduced.
- LibVLC picture presets are installed through VLC's native adjust filter at LibVLC instance creation. Media3 presets are installed before prepare. Selecting/previewing a preset restarts only the current channel after a short debounce so the decoder is not reconfigured mid-stream.
- Added Media3 rendered-frame monitoring and one native-rendering recovery attempt for a non-standard preset before confirmed-engine recovery.
- LibVLC now renders through TextureView so SurfaceTexture frame updates can be monitored. The watchdog distinguishes video-frame stalls from audio-only playback and ignores intentionally paused playback.
- Added unit tests for LibVLC uncapped Auto/Best Available, explicit quality caps, picture-filter options, paused playback and video-frame stall decisions.
- Updated source-only CI to verify resolved engine dependencies, compile Kotlin and run unit tests. APK assembly/upload steps are disabled until the user authorizes packaging after all remaining updates.
- Source-level feature checks also confirm the cinematic home, backup/restore, signed-lease cache, hidden-channel scan, 20k-row channel-index test, active-only cached playlist refresh, EPG matching, YouTube native-to-WebView fallback and playlist lock/deletion paths are present.
- **Still pending:** final source audit, latest source-only CI result, live Worker/signing end-to-end verification where applicable, and Android TV tests (owned by the user). No device-test result is inferred from source or CI.


## Final source-only check and licence gateway configuration — 7 November 2026

- Latest code commit `031e78c2af0beb90f65875b346a20944f142c557` passed [GitHub Actions run 42341597071](https://github.com/SoftPPJ/HomeTV-Next/actions/runs/42341597071). The workflow resolved the Media3/NextLib/LibVLC versions, compiled Kotlin and passed the unit tests.
- Dependency resolution confirmed Media3 1.11.1, NextLib Media3 extension 1.11.1-0.17.0, and LibVLC 3.7.7.
- The source-check workflow has no APK assembly or upload steps. This run produced no APK artifact. No release or prerelease was created.
- Cloudflare account inspection confirmed `hometv-license-next` is deployed with 100% traffic, has a `LICENSE_SIGNING_PRIVATE_JWK` secret binding, and its `UPSTREAM_BASE` points to the existing V6 licence Worker. Secret contents were not accessed. A live validation still requires a valid test licence/device pair and remains unverified.
- **Remaining:** Android TV playback and quality tests are user-owned; do not claim the freeze is definitively fixed until observed on the TV. No APK should be assembled until the user explicitly authorizes packaging.

