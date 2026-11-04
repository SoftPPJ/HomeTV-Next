package com.softppj.hometv

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Best-effort Android-only YouTube live HLS discovery.
 * Does not solve CAPTCHA, authentication, DRM, or restricted streams.
 * Run on a background thread; caller enforces an 8-second total deadline.
 */
internal object YouTubeStreamResolver {
    const val TIMEOUT_MS = 8_000
    data class Stream(val url: String, val kind: String)

    /** Extracts a supported 11-character video ID without making a network request. */
    internal fun videoId(videoUrl: String): String? {
        val uri = Uri.parse(videoUrl)
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        if (host !in setOf("youtube.com", "youtu.be", "youtube-nocookie.com")) return null
        val path = uri.path.orEmpty()
        val id = when {
            host == "youtu.be" -> path.trim('/').substringBefore('/')
            path == "/watch" -> uri.getQueryParameter("v")
            path.startsWith("/live/") || path.startsWith("/embed/") ->
                path.trim('/').substringAfter('/').substringBefore('/')
            else -> null
        } ?: return null
        return id.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }

    /**
     * Parses only the public player response. Keeping this separate makes the
     * security-sensitive acceptance rules testable without contacting YouTube.
     */
    internal fun extractHlsManifest(html: String): String? {
        val marker = "ytInitialPlayerResponse"
        val at = html.indexOf(marker)
        if (at < 0) return null
        val open = html.indexOf('{', at + marker.length)
        if (open < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        var close = -1
        for (i in open until html.length) {
            val ch = html[i]
            if (inString) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
            } else {
                if (ch == '"') inString = true
                else if (ch == '{') depth++
                else if (ch == '}' && --depth == 0) { close = i; break }
            }
        }
        if (close < 0) return null
        return try {
            val player = JSONObject(html.substring(open, close + 1))
            val playability = player.optJSONObject("playabilityStatus")?.optString("status")
            if (playability != "OK") return null
            val hls = player.optJSONObject("streamingData")?.optString("hlsManifestUrl").orEmpty()
            hls.takeIf { it.startsWith("https://") }
        } catch (_: Exception) {
            null
        }
    }

    fun resolve(videoUrl: String): Stream? {
        val id = videoId(videoUrl) ?: return null
        // Public watch-page metadata sometimes exposes HLS for live broadcasts.
        // Do not try to defeat challenges or access controls.
        val connection = (URL("https://www.youtube.com/watch?v=$id").openConnection() as HttpURLConnection)
        try {
            connection.connectTimeout = 3_000
            connection.readTimeout = 4_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "text/html")
            if (connection.responseCode !in 200..299) return null
            val html = connection.inputStream.bufferedReader().use { it.readText().take(2_000_000) }
            val hls = extractHlsManifest(html) ?: return null
            return Stream(hls, "hls")
        } catch (_: Exception) {
            return null
        } finally {
            connection.disconnect()
        }
    }
}
