# HomeTV-V4 — Android TV acceptance checklist

**Build:** HomeTV-V4.apk | **Version:** 4.0.0 | **Test device:** __________ | **Android TV version:** __________ | **Date:** __________

Tick **Pass** only after trying the feature on the TV. Record any failure with the channel name, URL (if safe to share), button sequence and a screenshot/video. **N/A** is acceptable for unavailable content.

## A. Installation, launch, security
- [ ] A1. APK installs and launches without crashing (if replacing V2/V3, signing certificate mismatch may require a separate installation plan).
- [ ] A2. Login/activation works with a valid license.
- [ ] A3. Wrong or expired license is rejected and protected playlist is not exposed.
- [ ] A4. License remains valid after force-stop and restart.
- [ ] A5. License surrender is available when needed; do **not** uninstall before confirming it.
- [ ] A6. App remains responsive after returning from Android TV Home/sleep.

## B. Playlists and channels
- [ ] B1. Default licensed playlist loads only after authentication.
- [ ] B2. Add an external M3U URL and verify its channels appear.
- [ ] B3. Refresh fetches updated remote M3U content, not just the cached list.
- [ ] B4. A large playlist (1000+ logical channels if available) loads without freezing.
- [ ] B5. Channel count reflects logical channels, not alternate URLs.
- [ ] B6. Multiple URLs for one channel appear as alternatives, not inflated channel count.
- [ ] B7. Channel groups, All Channels and favorites show the correct entries.
- [ ] B8. Search finds channels quickly in a large playlist.
- [ ] B9. Hide/unhide and lock/unlock channel/group persist after restart.
- [ ] B10. Channel logos do not freeze scrolling or exhaust memory.

## C. Playback engines and streams
- [ ] C1. Normal HLS (.m3u8) stream plays with Media3.
- [ ] C2. MPEG-TS / other available live formats play correctly.
- [ ] C3. If Media3 fails on a supported test stream, LibVLC fallback is attempted.
- [ ] C4. When LibVLC succeeds, the preferred engine is remembered on reopening the channel.
- [ ] C5. An explicitly selected engine remains selected after channel changes/restart.
- [ ] C6. With several URLs for one channel, a working URL is selected and manual URL selection persists.
- [ ] C7. If all URLs fail, the app skips or reports failure without crashing or looping.
- [ ] C8. Channel switching does not flash or resize incorrectly before applying the saved aspect ratio.
- [ ] C9. Universal aspect ratio and per-channel override work and persist.
- [ ] C10. Audio and picture quality controls do not interrupt unrelated channels.
- [ ] C11. FFmpeg-backed audio fallback: test a stream with an audio codec unsupported by Android's native decoder, if available; confirm sound. This is an **audio decoder extension**, not a new video engine.
- [ ] C12. FFmpeg integration does not regress normal Media3 playback or LibVLC fallback.

## D. YouTube
- [ ] D1. YouTube Live URL plays in the embedded WebView.
- [ ] D2. Unmute/mute control is accessible and not covered by YouTube overlay buttons.
- [ ] D3. Switching from YouTube to a regular channel and back works.
- [ ] D4. Back/OK/arrow keys behave correctly on the YouTube player.

## E. TV remote and settings
- [ ] E1. Up/Down navigates channels and channel list consistently.
- [ ] E2. Left/Right opens the intended group/channel settings and focus is visible.
- [ ] E3. OK selects a highlighted control, and Back closes the correct screen.
- [ ] E4. Aspect-ratio choices preview immediately; OK confirms the choice.
- [ ] E5. Every ON/OFF toggle changes the corresponding behavior, including background URL checks.
- [ ] E6. All manual selections survive an app restart.
- [ ] E7. Channel list opens at the currently playing channel in the selected group.
- [ ] E8. Rapid remote presses do not crash or lock the app.

## F. EPG, subtitles and timer
- [ ] F1. XMLTV EPG source loads and matches channel tvg-id/name.
- [ ] F2. Now/Next information appears correctly while changing channels.
- [ ] F3. TV Guide 30-minute earlier/later navigation works with the remote.
- [ ] F4. Selecting a future programme allows auto-switch or reminder.
- [ ] F5. Scheduled auto-switch works while HomeTV is in foreground, without bypassing channel locks.
- [ ] F6. Reminders appear and can be cancelled.
- [ ] F7. Scheduled programmes survive closing/reopening the app.
- [ ] F8. Media3 subtitle Off/Automatic/language selection works where subtitles are available.
- [ ] F9. Subtitle preference remains saved for that channel.
- [ ] F10. Sleep timer can be set/cancelled and stops playback after the selected interval.

## G. Stability
- [ ] G1. Watch a channel continuously for 30–60 minutes without a crash.
- [ ] G2. Switch channels repeatedly for 5 minutes; no decoder or memory failure.
- [ ] G3. Refresh a large playlist while playback is active.
- [ ] G4. Background stream checks do not interrupt the current channel.
- [ ] G5. Exit and reopen the app several times; no loss of settings.

## Test summary
- Passed: _____ / Failed: _____ / Not tested: _____
- Device/model: ______________________
- Major failures and exact reproduction steps: ______________________
- Final decision: [ ] Accept [ ] Fix and rebuild

## Important notes
- Media3 FFmpeg extension uses a third-party Jellyfin build (version 1.5.0+1) and supplies software **audio** decoding. Default platform decoders are preferred.
- Jellyfin's FFmpeg artifact is declared under **GPL-3.0**. Before redistributing the APK, review GPL obligations and provide applicable source and license notices.
- GitHub Actions debug builds may use different signing certificates; upgrading in place can fail. Do not uninstall a licensed installation without a license-recovery plan.
- Compilation success does not establish TV compatibility. All items above require actual device testing.
