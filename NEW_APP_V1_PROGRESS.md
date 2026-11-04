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
- The latest audio, playlist-refresh, large-list navigation, auto-unhide, and refresh/deletion safety changes have not been compiled yet. No build was triggered for them.

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

## Not yet verified or completed
- Compile the latest source changes; no build has been run since the recent edits.
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
