# V6 playback-engine implementation gate

Current inspected source: MainActivity.kt uses Media3 1.5.1 and LibVLC 3.5.1. handleEngineFailure() iterates alternate URLs with these two engines for unconfirmed channels. Confirmed channels retry their saved engine. YouTube is routed to WebView. No IJKPlayer dependency is present.

## Next engineering changes (not yet implemented)
- Extract playback session/engine state from MainActivity.kt behind a cancellable, generation-checked controller.
- Keep per-channel confirmed engine + URL and user manual override, and preserve these across refreshes.
- Track attempted (URL, engine) pairs per discovery session; never retry a failed pair until a new explicit retry or a new session.
- Bound start time and release player/surface resources before switching.
- Preserve WebView YouTube routing, with no IJK probing of YouTube watch pages.
- Evaluate a maintained IJK fork for licensing, Android ABI, dependency conflicts, memory and actual additional successful streams. Do not add it to production Gradle before passing these gates.
- Only when approved, add IJK as an opt-in third fallback after Media3 and LibVLC, and persist success per channel.

## Required tests
- Media3 succeeds: LibVLC and IJK never initialize.
- Media3 fails, LibVLC succeeds: remember LibVLC; do not re-probe Media3 on subsequent opens.
- Both fail: try IJK only if enabled and validated; otherwise honor auto-skip and manual override.
- Multiple URLs: do not count URLs as separate logical channels.
- Rapid switching and HOME/BACK: stale callbacks cannot restart an old channel.
- YouTube and normal IPTV regression, 1k/5k/10k playlist memory, long playback.
- Compare same stream URL for player success, startup latency, resolution, dropped frames, audio sync, memory and crash rate.

This document is an implementation plan, not evidence that the third engine is integrated or tested.

## Implementation update — stale delayed fallback
- Commit 58c697a: the delayed initial-discovery fallback Runnable now captures playbackGeneration and checks it before calling playIndex. This blocks that particular delayed callback from restarting a replaced session.
- Other delayed recovery callbacks still require review. Compilation and Android TV regression tests have not been run for this commit.

## Implementation update — reconnect generation guards
- Commit 0ce0a7c: added playbackGeneration checks to delayed confirmed-stream reconnect and stream-stopped retry tasks, preventing these particular tasks from restarting a replaced playback session.
- Network-recovery polling, player event listeners, engine selection, compilation and device tests remain to be reviewed/verified.

## Implementation update — network recovery ownership
- Commit 029bdd3: network recovery polling now captures playbackGeneration and checks both generation and task identity before retrying playback or rescheduling. This prevents an outdated polling task from reviving a replaced session.
- Source change only; Kotlin compilation and real device tests are still pending. Other asynchronous callbacks and third-engine evaluation remain open.

## Implementation update — confirmed URL scoping
- Commit 65c67a3: handleEngineFailure treats a saved engine as confirmed only when the current channel URL equals the persisted working URL. This avoids applying the proven-source reconnect policy to an unproven alternative URL.
- Follow-up needed: inspect engineFor and rememberWorking consistency when automatic fallback succeeds on a different URL, and verify manual override behavior. Not compiled or device-tested.

## Implementation update — confirmed engine/URL pairing
- Commit d3fbeb6: rememberWorking now persists the confirmed engine and URL together after actual playback, except when testing an alternative or preserving a different manually pinned URL. engineFor only uses channel-level confirmed engine when current URL matches working URL.
- Remaining: confirm manual engine overrides and alternative test promotion behavior, run Kotlin compile and APK build, and test on Android TV. This is source-only, unverified.

## Implementation update — manual alternative stream selection
- Commit f700e19: switchAlternative and selectAlternative persist manual_url but clear previous working_url and confirmed_engine; a new stream must actually play before being marked confirmed. Existing manual engine preferences are retained.
- Caveat: clearing the previous confirmed pair on manual selection is intentional for discovery but needs regression testing, including returning to a previous source and honoring pinned engine behavior. Not compiled or device-tested.
