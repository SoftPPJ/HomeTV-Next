package com.softppj.hometv

internal data class ChannelIndexSnapshot(
    val indicesByKey: Map<String, List<Int>>,
    val representatives: List<Int>,
    val liveRepresentatives: List<Int>,
    val perPlaylistNumbers: Map<String, Int>,
    val continuousNumbers: Map<String, Int>
)

internal object HomeTvPolicies {
    fun channelKey(channel: Channel): String =
        channel.mediaType + "|" +
            channel.source.trim().lowercase(java.util.Locale.ROOT) + "|" +
            channel.name.trim().lowercase(java.util.Locale.ROOT) + "|" +
            channel.group.trim().lowercase(java.util.Locale.ROOT)

    /** Build a logical-channel index in one ordered pass; alternate URLs share a number. */
    fun buildChannelIndex(channels: List<Channel>): ChannelIndexSnapshot {
        val groups = linkedMapOf<String, MutableList<Int>>()
        channels.forEachIndexed { index, channel ->
            groups.getOrPut(channelKey(channel)) { mutableListOf() }.add(index)
        }
        val representatives = groups.values.map { it.first() }
        val live = representatives.filter { channels[it].mediaType == "live" }

        val counts = mutableMapOf<String, Int>()
        val perPlaylist = LinkedHashMap<String, Int>(live.size)
        live.forEach { index ->
            val channel = channels[index]
            val playlistKey = channel.source.trim().lowercase(java.util.Locale.ROOT)
            val number = (counts[playlistKey] ?: 0) + 1
            counts[playlistKey] = number
            perPlaylist[channelKey(channel)] = number
        }
        val continuous = LinkedHashMap<String, Int>(live.size)
        live.forEachIndexed { position, index ->
            continuous[channelKey(channels[index])] = position + 1
        }
        return ChannelIndexSnapshot(groups, representatives, live, perPlaylist, continuous)
    }

    fun resolvePlaylistSource(
        selected:String?,
        current:String?,
        enabled:List<String>,
        available:Set<String>
    ):String?{
        val enabledSet=enabled.toHashSet()
        return selected?.takeIf{it in enabledSet && it in available}
            ?: current?.takeIf{it in enabledSet && it in available}
            ?: enabled.firstOrNull{it in available}
    }

    /** Give live IPTV a longer first-frame grace; HLS/DASH VOD also need segment startup time. */
    fun startupTimeoutMs(mediaType: String, streamKind: String): Long = when {
        mediaType == "live" -> 30_000L
        streamKind == "hls" || streamKind == "dash" || streamKind == "smooth" -> 25_000L
        else -> 15_000L
    }

    fun shouldAttemptNativeYoutube(
        eligibleVideo: Boolean,
        alreadyAttempted: Boolean,
        hasResolvedStream: Boolean,
        forceWeb: Boolean
    ): Boolean = eligibleVideo && !alreadyAttempted && !hasResolvedStream && !forceWeb

    fun shouldFallbackNativeYoutube(
        hasResolvedStream: Boolean,
        isCurrentChannel: Boolean,
        isSameScreen: Boolean
    ): Boolean = hasResolvedStream && isCurrentChannel && isSameScreen

    fun isReachableHttpResponse(statusCode: Int, contentType: String?): Boolean =
        statusCode in 200..299 && !contentType.orEmpty().contains("text/html", ignoreCase = true)

    fun shouldAutoUnhide(enabled: Boolean, isHidden: Boolean, playbackConfirmed: Boolean): Boolean =
        enabled && isHidden && playbackConfirmed

    fun <T> matchEpg(guide: Map<String, List<T>>, epgId: String, channelName: String): List<T> {
        val id = epgId.trim().lowercase(java.util.Locale.ROOT)
        val name = channelName.trim().lowercase(java.util.Locale.ROOT)
        return guide[id]?.takeIf { it.isNotEmpty() } ?: guide[name] ?: emptyList()
    }

    fun nextClockValue(values: List<String>, currentIndex: Int): Pair<String, Int>? {
        if (values.isEmpty()) return null
        val index = ((currentIndex % values.size) + values.size) % values.size
        return values[index] to ((index + 1) % values.size)
    }

    /** Keep Auto/Best Available uncapped; only explicit user quality presets set a height ceiling. */
    fun libVlcAdaptiveOptions(quality: String): List<String> = when (quality) {
        "Data Saver (360p)" -> listOf(":adaptive-maxheight=360", ":adaptive-logic=highest")
        "Standard (480p)" -> listOf(":adaptive-maxheight=480", ":adaptive-logic=highest")
        "HD (720p)" -> listOf(":adaptive-maxheight=720", ":adaptive-logic=highest")
        "Full HD (1080p)" -> listOf(":adaptive-maxheight=1080", ":adaptive-logic=highest")
        "Auto", "Best Available" -> listOf(":adaptive-logic=highest")
        else -> emptyList()
    }

    /** Apply the HTTP headers supported by the bundled LibVLC 3.x HTTP access module. */
    fun libVlcHttpHeaderOptions(headers:Map<String,String>):List<String> = buildList {
        headers["User-Agent"]?.takeIf{it.isNotBlank() && '\r' !in it && '\n' !in it}
            ?.let{add(":http-user-agent="+it)}
        headers["Referer"]?.takeIf{it.isNotBlank() && '\r' !in it && '\n' !in it}
            ?.let{add(":http-referrer="+it)}
        headers["Origin"]?.takeIf{it.isNotBlank() && '\r' !in it && '\n' !in it}
            ?.let{add(":http-origin="+it)}
    }

    /** VLC's adjust filter is configured on the LibVLC instance, before the player opens a stream. */
    fun libVlcPictureOptions(
        mode: String,
        brightness: Float,
        contrast: Float,
        saturation: Float
    ): List<String> = if (mode == "Standard") emptyList() else listOf(
        "--video-filter=adjust",
        "--brightness=$brightness",
        "--contrast=$contrast",
        "--saturation=$saturation"
    )

    /** Video streams need a presented frame; audio-only streams need known tracks and startup progress. */
    fun shouldConfirmVlcPlayback(
        videoExpected: Boolean,
        framePresented: Boolean,
        videoTracksKnown: Boolean,
        playbackAgeMs: Long,
        minimumAudioOnlyAgeMs: Long = 1_500L
    ): Boolean = if (videoExpected) framePresented
        else videoTracksKnown && playbackAgeMs >= minimumAudioOnlyAgeMs

    fun isVideoOutputStalled(
        isPlaying: Boolean,
        videoExpected: Boolean,
        videoFrameAgeMs: Long,
        mediaProgressAgeMs: Long,
        timeoutMs: Long = 20_000L
    ): Boolean {
        if (!isPlaying) return false
        return if (videoExpected) videoFrameAgeMs >= timeoutMs
        else mediaProgressAgeMs >= timeoutMs
    }
}
