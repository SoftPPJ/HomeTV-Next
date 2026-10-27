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
- [ ] IJKPlayer evaluation: maintained fork, security/license review, ABI compatibility, measured benefit before bundling
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
