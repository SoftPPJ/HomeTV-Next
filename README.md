# HomeTV

Native Android TV + Android phone IPTV player (early development build).

## Implemented in source
- Add, edit, enable/disable, and delete multiple remote HTTP(S) M3U playlists.
- Combine enabled playlists into grouped channel navigation.
- Play compatible IPTV streams with Android Media3 ExoPlayer.
- Embed YouTube Live videos in a WebView when YouTube allows embedding.
- Lock channels and groups with a 4–6 digit PIN (SHA-256 digest stored locally; no secure hardware-backed credential storage).
- Remember last channel and optionally resume when app launches.
- Android TV remote left/right and channel up/down shortcuts while watching.
- Settings available on TV and phone.
- Optional boot receiver: Android 10+ prevents ordinary apps from freely launching an Activity in the background. TV power-button interception is not supported.

## Build an APK
Open **Actions → Build HomeTV APK → Run workflow**, or push an Android source change to main.
If the build succeeds, download the **HomeTV-debug** artifact from the workflow run.
Debug APK is intended for testing, not Play Store release.

## Known limitations
- Android TV startup is device/OS dependent; on Android 10+ the boot receiver intentionally does not launch the app.
- YouTube embedding may fail on broadcasts that disallow it.
- Remote navigation, UI scaling, HLS behavior, and build success require testing on actual devices.
- The M3U parser handles basic EXTINF entries; extended tags, fallback streams, and EPG support are not yet complete.
- PIN lock is a convenience restriction, not a hardened parental-control security boundary. Additional protection is required before relying on it.
- Playlist errors are currently summarized; detailed diagnostics remain to be added.
- No signed release APK has been verified.

The IPTV playlist repository is not modified by this app.
