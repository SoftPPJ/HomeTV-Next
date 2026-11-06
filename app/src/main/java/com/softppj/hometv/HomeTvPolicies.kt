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
}
