# HomeTV-Next playback-engine implementation and verification gate

**Target:** `SoftPPJ/HomeTV-Next` / `new-app-v1-development`

This file replaces the older V6 engine plan. HomeTV-Next is a two-engine player. IJKPlayer is not included. FFmpeg is retained only as a Media3 decoder extension, not as a third playback engine.

## Current engine stack

- **AndroidX Media3:** 1.11.1 across ExoPlayer, HLS, DASH, RTSP, SmoothStreaming, UI and video effects.
- **Media3 FFmpeg decoder extension:** 1.9.0+1. It is loaded after Android's native renderers, with decoder fallback enabled; it is not a separate player engine.
- **LibVLC Android:** 3.7.7.
- **Build toolchain:** Android Gradle Plugin 8.13.2, Kotlin 2.2.10, Gradle 8.13, compile SDK 36, Java 17.

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
