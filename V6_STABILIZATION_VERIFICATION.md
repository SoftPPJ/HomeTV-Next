# HomeTV V6 — Nine-objective stabilization gates

Branch: `my-home-tv-v6-development`. This file is a verification plan, not a claim of passed tests.

## Release gates (all nine must be verified)

- [ ] 1. Playback: HLS, DASH, SmoothStreaming, RTSP, RTMP, UDP/RTP, MP4 and supported codecs; websites with HTML5 video/iframes; YouTube. Record actual device, URL, result and logs. Flash/DRM may be unsupported.
- [ ] 2. Auto player routing: for each applicable protocol, confirm first engine, fallback order, real first-frame confirmation, and no false "working" status.
- [ ] 3. Preserve confirmed URL/engine: restart app and verify reuse; manual engine and URL never silently change; failed Media3 not retried on an already confirmed LibVLC URL.
- [ ] 4. Web links: arbitrary HTML URL detected by HTTP content; embedded player fullscreen/autoplay where browser allows; cross-origin and cookie restrictions handled gracefully. Original playlist group is unchanged.
- [ ] 5. Remote: UP/DOWN, CH+/CH−, OK, LEFT, RIGHT, BACK and numeric selection in native and WebView fullscreen; BACK from playback returns to selection/Home and never traps the user.
- [ ] 6. Large playlists: import/refresh at 1k, 5k, 10k channels, duplicates/multiple URLs; verify UI responsiveness, memory, logical channel count and OFF toggles; no playback interruptions from background probes.
- [ ] 7. Recovery: timeout, buffering, dead URL, offline→online, auto-reconnect OFF, auto-skip OFF, cancelled callbacks, retries bounded; never disturb an already healthy stream.
- [ ] 8. Persistence: favorites, hidden/locked/PIN, per-channel aspect, manual engine/URL, last channel, playlist and viewer preferences survive restart; manual choices retained until changed.
- [ ] 9. Compilation: run Gradle compile with Android SDK, fix all compiler errors, then Android TV smoke test; do not call uncompiled commits verified. APK generation and CI builds require available quota or explicit authorization.

## No-quota policy

Do not modify build workflow solely to trigger GitHub Actions while quota is exhausted. Source-only commits and static inspection are not compilation. When compilation is unavailable, mark gate 9 BLOCKED, not PASS.

## Current status

- Smart routing and HTTP content classification are committed.
- Stale HTTP classification cancellation on BACK/stop and PIN-before-probe safeguard committed in `bf3f959`.
- All nine gates remain **unverified** until actual compilation and device tests are recorded.
