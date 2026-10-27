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
