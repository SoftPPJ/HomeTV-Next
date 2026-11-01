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
- Removed the three IJKPlayer Gradle dependencies. Old saved IJK engine selections are migrated to Auto; saved IJK-confirmed engine/URL pairs are cleared so the app can establish a supported engine again.
- Startup: cached playlists are now parsed concurrently with a bounded three-thread pool; resuming a valid saved channel no longer builds/sorts the full surfing list first; ambiguous HTTP URL routing is cached across app restarts for 24 hours and first-time route checks use shorter timeouts.
- Exit/memory: final Activity destruction clears in-memory poster/route/player-session caches and pending main-thread callbacks; Android memory-pressure callbacks evict poster bitmaps. Persistent playlist files, preferences and validated licence cache are intentionally retained to keep startup fast and offline licensing safe.
- Source and dependency changes only. Kotlin compilation, APK assembly, and Android TV playback/startup/exit tests have NOT yet been run. Do not mark release gates passed or publish a prerelease.
