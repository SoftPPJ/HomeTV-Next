# HomeTV-Next playback-engine implementation and verification gate

**Target:** `SoftPPJ/HomeTV-Next` / `new-app-v1-development`

This file replaces the older V6 engine plan. HomeTV-Next is a two-engine player. IJKPlayer is not included. FFmpeg is retained only as a Media3 decoder extension, not as a third playback engine.

## Current engine stack

- **AndroidX Media3:** 1.11.1 across ExoPlayer, HLS, DASH, RTSP, SmoothStreaming, UI and video effects.
- **Media3 FFmpeg decoder extension:** NextLib 1.11.1-0.17.0, aligned to Media3 1.11.1. HomeTvRenderersFactory subclasses NextRenderersFactory so native platform renderers retain priority and FFmpeg renderers are available as extension fallback; it is not a separate player engine. The extension is GPL-3.0 and distribution must retain the applicable notices/source obligations.
- **LibVLC Android:** 3.7.7.
- **Build toolchain:** Android Gradle Plugin 8.13.2, Kotlin 2.4.21, Gradle 8.13, compile SDK 36, Java 17.

## Quality policy

- Media3 Auto leaves adaptive track selection uncapped; Best Available requests the highest supported bitrate. Explicit user quality presets may set a resolution ceiling.
- LibVLC Auto and Best Available use `:adaptive-logic=highest` without an `:adaptive-maxheight` cap. Only explicit Data Saver / Standard / HD / Full HD choices add a maximum height.
- LibVLC enables automatic hardware decoding with `--avcodec-hw=any`, retains network/live caching, and does not re-encode the source.
- LibVLC picture presets use VLC's native `adjust` video filter configured on the LibVLC instance. Standard/Engine Native leaves that filter disabled.
- Picture preview/commit recreates the current channel session after a short debounce so picture effects are installed before decoder preparation rather than changing a live TV decoder pipeline.

## Frozen-picture protection

- Media3 tracks rendered decoder-frame progress and the first rendered video frame. If audio/playback continues but video output stops for 20 seconds, it first tries one native-rendering recovery when a non-standard picture effect is active; if that does not recover, it enters the existing confirmed-engine recovery path.
- LibVLC uses a TextureView frame-update callback, together with video-output/track state, to detect actual presented-frame stalls rather than trusting the audio/media clock. A 20-second video-frame stall enters recovery. Intentionally paused playback is excluded; audio-only streams use media-time progress.
- A video source is not marked as a confirmed working LibVLC source until a real video frame has been presented. Audio-only playback can be confirmed after playback settles.
- The established-channel policy remains: retry the saved URL on its confirmed engine; probe alternatives in the background; do not silently replace the saved URL or engine.

## Verification status

- Source-only CI now checks resolved playback dependencies, compiles Kotlin, and runs unit tests.
- Unit tests cover uncapped LibVLC Auto/Best Available, explicit quality ceilings, native versus adjusted picture options, paused playback and video-frame stall decisions.
- CI intentionally does **not** assemble or upload an APK while the remaining source updates are being completed.
- Android TV playback, decoder compatibility, original-resolution output, long-run stability and remote-control acceptance remain device tests for the user. Do not mark them passed without user confirmation.

**Release gate:** no APK, prerelease or release until all remaining source work is complete, source-only checks pass, and the user explicitly authorizes packaging.


## Latest source-only verification — 7 November 2026

- Commit `031e78c2af0beb90f65875b346a20944f142c557` passed GitHub Actions run [42341597071](https://github.com/SoftPPJ/HomeTV-Next/actions/runs/42341597071): dependency-insight commands, Kotlin compilation and all unit tests. That run did not assert the exact NextLib artifact version, so it is not treated as final dependency verification.
- The dependency is now pinned to the published NextLib release 1.11.1-0.17.0. The current source-only workflow explicitly asserts Media3 1.11.1, LibVLC 3.7.7 and NextLib 1.11.1-0.17.0; the new run is the final version check.
- No APK assembly, APK upload, release or prerelease occurred in this run. The run has no uploaded artifacts.
- Cloudflare configuration inspection found the `hometv-license-next` Worker deployed at 100% traffic, with `LICENSE_SIGNING_PRIVATE_JWK` present as a secret binding and `UPSTREAM_BASE` configured to the existing V6 licensing Worker. No secret value was read. A live signed validation was not attempted because no test licence/device credentials were supplied.
- Android TV behavior is still unverified and remains the user's test. Do not infer freeze-free playback or original stream quality from source checks.



## Cross-version review and final LibVLC watchdog correction — 7 November 2026

The Next engine stack supersedes the V6 snapshot's older Media3 1.5.1 / LibVLC 3.5.1 / Jellyfin FFmpeg 1.5.0+1 stack. Next is deliberately a two-player architecture; NextLib FFmpeg remains a decoder extension under Media3, not a third engine.

The final source audit corrected two LibVLC confirmation/watchdog gaps:
- The 20-second video-frame watchdog now runs even when the user's Auto-reconnect setting is OFF. Detection and diagnostics remain active; the established-channel recovery path still respects Auto-reconnect and does not silently change the selected URL or engine.
- A LibVLC Playing event no longer confirms a video stream before a real TextureView frame is presented. Audio-only confirmation requires a known track list and startup progress.

Unit tests now cover video-frame-required confirmation and audio-only confirmation timing. The new source-only CI run must pass before this gate is considered source-verified.
