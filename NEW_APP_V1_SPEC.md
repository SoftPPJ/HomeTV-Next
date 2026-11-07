# My Home TV – New App V1 Development Specification

## Project direction and safety
- This is a new Android TV application, developed on the isolated branch `new-app-v1-development`.
- It must install alongside the existing My Home TV V6. Use a distinct application ID (`com.softppj.myhometv.next`) and separate app-private storage, preferences, cache, and signed-lease cache. Per the user's explicit approval on 2026-11-05, the Next V1 gateway uses its own signing key but delegates activation/device records to the existing V6 licensing backend. Do not change the V6 Worker source or existing licence records.
- Do not modify the existing V6 branch, existing releases, or existing installed app data.
- Do not publish an APK, prerelease, or release until the user explicitly approves.
- Preserve the PPJSoft / My Home TV identity unless the user requests a new name or logo.

## Startup and playlists
- Start immediately from the last valid cached playlist and last watched channel; do not block first picture on network refresh.
- On each app start, check and update playlists in the background when the Settings toggle "Automatic Playlist Check & Update" is enabled.
- The toggle must persist across restarts. Show concise progress/result status without interrupting playback.
- Do not repeatedly parse unchanged sources. Use source modification/ETag/hash metadata where available.
- Refresh only the active playlist by default; preserve inactive playlist data and state.
- Handle large playlists (10,000+ channels) with background parsing, indexed/lazy list rendering, bounded memory use, and no main-thread network or parsing work.
- Playlist deletion must immediately remove its channels; if the active playlist is deleted, move playback to the next active playlist's last-watched channel.
- Channel numbering is per playlist by default. An optional all-active-playlists view may use continuous numbering.
- Preserve playlist add/manage/lock and backup/restore functionality.

## Hidden channels
- Scan hidden channels after startup in bounded background batches; never delay startup or interrupt current playback.
- Settings toggle: "Auto Unhide Working Channels", default OFF.
- OFF: report channels confirmed working and let the user unhide them manually.
- ON: automatically unhide only after a positive availability/playback confirmation.
- A timeout, network error, or inconclusive probe must never count as working.
- Provide a review/status view of channels discovered and restored.

## Licensing and connectivity
- If there is no internet, show a clear "No Internet Connection" state; do not repeatedly prompt for activation merely because the network is unavailable.
- Validate a signed cached licence offline, bound to the device, with the agreed seven-day offline grace and expiry/clock rollback safeguards.
- Check cached expiry locally at startup; perform network revalidation in the background when connectivity returns.
- Clearing app cache must not release the device licence. Licence secrets/state must not be included in user backups.
- Do not treat temporary connection failures as a revoked or invalid licence.

## Date and time overlay
- Overlay date, time, and day directly over video with readable shadow and no opaque background.
- Rotate/blink through date → time → day one at a time with smooth transitions.
- Keep the overlay unobtrusive and configurable if display controls already exist.

## EPG and channel information
- Show current and next programme information alongside channel information while changing channels and during the info overlay.
- Support XMLTV EPG matching, local timezone presentation, schedule browsing, and existing reminder/scheduled-switch functionality where present.
- Missing or stale EPG data must not block channel playback.

## YouTube – final implementation, not experimental
- Retain the experimental YouTube improvements and finish them as supported app functionality.
- Attempt native stream playback first; if resolution detection times out (target 8 seconds) or playback fails, fall back cleanly to the WebView/browser playback route.
- Settings quality preference: Best Available (default), Prefer 1080p, Prefer 720p, and Auto. Treat these as preferences; do not promise a resolution YouTube does not provide.
- Automatically unmute when YouTube playback starts, with a user setting if a mute preference already exists.
- Make Android TV remote navigation, focus handling, play/pause, seek, video selection, Back/exit, and overlay dismissal reliable.
- Resolve the remote-control overlap/focus issues and ensure YouTube failure cannot crash or disrupt IPTV playback.
- Preserve YouTube favourites and settings; add useful diagnostic logging for resolver and playback failures.
- Do not implement proxy/VPN or geographic restriction bypass.

## Playback and TV experience
- Keep the premium cinematic Android TV interface and remote-first navigation.
- Support HLS, DASH/MPD, SmoothStreaming, direct MP4, and WebTV sources where technically supported.
- Preserve original broadcast quality; do not silently downscale.
- Keep Media3 as the primary engine and LibVLC as the second/fallback engine. FFmpeg is only a Media3 decoder extension, not a third player; IJKPlayer is not part of this app.
- Never silently switch a user's manually selected working URL/engine. Offer confirmed alternatives without automatic switching.
- Keep connection-loss feedback, bounded reconnect behaviour, per-channel settings, favourites, continue watching, diagnostics, and safe-start protection.
- Test startup, navigation, and playback against large playlists and malformed/unavailable streams.

## Validation and release gates
- Compile and assemble debug APK successfully before requesting device testing.
- Add automated tests for playlist parsing/indexing, hidden-channel decisions, licence offline states, date/time rotation, EPG matching, and YouTube fallback/state handling.
- Run Android TV device tests for remote navigation, playback quality, YouTube, EPG, and side-by-side installation.
- Keep a clear checklist of implemented, automated-tested, and device-tested items. Never mark device tests passed without user confirmation.
- No final APK/release until critical build checks pass and the user approves.
