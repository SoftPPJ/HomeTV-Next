package com.softppj.hometv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class YouTubeStreamResolverTest {
    @Test
    fun extractsVideoIdsFromSupportedUrlForms() {
        val id = "AbCdEfGhI_1"
        assertEquals(id, YouTubeStreamResolver.videoId("https://youtu.be/$id"))
        assertEquals(id, YouTubeStreamResolver.videoId("https://www.youtube.com/watch?v=$id"))
        assertEquals(id, YouTubeStreamResolver.videoId("https://m.youtube.com/live/$id"))
        assertEquals(id, YouTubeStreamResolver.videoId("https://youtube.com/embed/$id"))
        assertEquals(id, YouTubeStreamResolver.videoId("https://www.youtube-nocookie.com/embed/$id"))
    }

    @Test
    fun rejectsUnsupportedHostsAndMalformedVideoIdsWithoutNetworkAccess() {
        assertNull(YouTubeStreamResolver.videoId("https://example.com/watch?v=AbCdEfGhI_1"))
        assertNull(YouTubeStreamResolver.videoId("https://youtube.com.evil.example/watch?v=AbCdEfGhI_1"))
        assertNull(YouTubeStreamResolver.videoId("https://youtube.com/watch?v=short"))
        assertNull(YouTubeStreamResolver.videoId("not a URL"))
    }

    @Test
    fun extractsHttpsHlsOnlyWhenPlayerStatusIsOk() {
        val player = JSONObject()
            .put("playabilityStatus", JSONObject().put("status", "OK"))
            .put("streamingData", JSONObject().put("hlsManifestUrl", "https://cdn.example/live/master.m3u8?token=a"))
        val html = "window.ytInitialPlayerResponse = $player;"
        assertEquals(
            "https://cdn.example/live/master.m3u8?token=a",
            YouTubeStreamResolver.extractHlsManifest(html)
        )
    }

    @Test
    fun rejectsNonPlayableAndNonHttpsManifests() {
        val unavailable = JSONObject()
            .put("playabilityStatus", JSONObject().put("status", "LOGIN_REQUIRED"))
            .put("streamingData", JSONObject().put("hlsManifestUrl", "https://cdn.example/live.m3u8"))
        assertNull(YouTubeStreamResolver.extractHlsManifest("ytInitialPlayerResponse = $unavailable;"))

        val insecure = JSONObject()
            .put("playabilityStatus", JSONObject().put("status", "OK"))
            .put("streamingData", JSONObject().put("hlsManifestUrl", "http://cdn.example/live.m3u8"))
        assertNull(YouTubeStreamResolver.extractHlsManifest("ytInitialPlayerResponse = $insecure;"))
    }

    @Test
    fun rejectsMissingOrMalformedPlayerResponse() {
        assertNull(YouTubeStreamResolver.extractHlsManifest("<html>no player data</html>"))
        assertNull(YouTubeStreamResolver.extractHlsManifest("ytInitialPlayerResponse = {broken"))
        assertNull(
            YouTubeStreamResolver.extractHlsManifest(
                "ytInitialPlayerResponse = " +
                    JSONObject().put("playabilityStatus", JSONObject().put("status", "OK")) +
                    ";"
            )
        )
    }
}
