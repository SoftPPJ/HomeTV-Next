package com.softppj.hometv

import org.junit.Assert.*
import org.junit.Test

class HomeTvPoliciesTest {
    @Test fun playlistScopeUsesSelectedThenCurrentThenFirstAvailable(){
        val enabled=listOf("HomeTV Live","TV","News")
        val available=setOf("TV","News")
        assertEquals("News",HomeTvPolicies.resolvePlaylistSource("News","TV",enabled,available))
        assertEquals("TV",HomeTvPolicies.resolvePlaylistSource("Missing","TV",enabled,available))
        assertEquals("TV",HomeTvPolicies.resolvePlaylistSource("Disabled","Disabled",enabled,available))
        assertNull(HomeTvPolicies.resolvePlaylistSource("Missing",null,enabled,emptySet()))
    }

    @Test fun playlistNumberingTreatsSourceNameCaseConsistently(){
        val channels=listOf(
            Channel("One","https://a/1","News",source="Playlist A"),
            Channel("Two","https://a/2","News",source="playlist a")
        )
        val index=HomeTvPolicies.buildChannelIndex(channels)
        assertEquals(1,index.perPlaylistNumbers[HomeTvPolicies.channelKey(channels[0])])
        assertEquals(2,index.perPlaylistNumbers[HomeTvPolicies.channelKey(channels[1])])
    }

    @Test fun libVlcReceivesSupportedPlaylistHttpHeaders(){
        assertEquals(
            listOf(
                ":http-user-agent=HomeTV-Test",
                ":http-referrer=https://ref.example/",
                ":http-origin=https://origin.example"
            ),
            HomeTvPolicies.libVlcHttpHeaderOptions(mapOf(
                "User-Agent" to "HomeTV-Test",
                "Referer" to "https://ref.example/",
                "Origin" to "https://origin.example"
            ))
        )
        assertTrue(HomeTvPolicies.libVlcHttpHeaderOptions(mapOf("Origin" to "bad\r\nheader")).isEmpty())
    }

    @Test fun reachabilityRejectsHtmlAndErrors() {
        assertTrue(HomeTvPolicies.isReachableHttpResponse(200, "application/vnd.apple.mpegurl"))
        assertFalse(HomeTvPolicies.isReachableHttpResponse(200, "text/html"))
        assertFalse(HomeTvPolicies.isReachableHttpResponse(404, "video/mp2t"))
        assertFalse(HomeTvPolicies.isReachableHttpResponse(503, null))
    }

    @Test fun autoUnhideRequiresPlaybackConfirmation() {
        assertFalse(HomeTvPolicies.shouldAutoUnhide(false, true, true))
        assertFalse(HomeTvPolicies.shouldAutoUnhide(true, false, true))
        assertFalse(HomeTvPolicies.shouldAutoUnhide(true, true, false))
        assertTrue(HomeTvPolicies.shouldAutoUnhide(true, true, true))
    }

    @Test fun epgMatchesTvgIdBeforeName() {
        val guide = mapOf("asianet.cinema" to listOf("ID"), "asianet cinema" to listOf("Name"))
        assertEquals(listOf("ID"), HomeTvPolicies.matchEpg(guide, "ASIANET.CINEMA", "Asianet Cinema"))
        assertEquals(listOf("Name"), HomeTvPolicies.matchEpg(guide, "missing", "Asianet Cinema"))
    }

    @Test fun dateTimeDayRotationWrapsAndHandlesNegativeIndex() {
        val values = listOf("06 Nov 2026", "18:30", "Friday")
        val a = HomeTvPolicies.nextClockValue(values, 0)!!
        val b = HomeTvPolicies.nextClockValue(values, a.second)!!
        val c = HomeTvPolicies.nextClockValue(values, b.second)!!
        val d = HomeTvPolicies.nextClockValue(values, c.second)!!
        assertEquals("06 Nov 2026", a.first)
        assertEquals("18:30", b.first)
        assertEquals("Friday", c.first)
        assertEquals("06 Nov 2026", d.first)
        assertEquals("Friday", HomeTvPolicies.nextClockValue(values, -1)!!.first)
        assertNull(HomeTvPolicies.nextClockValue(emptyList(), 0))
    }

    @Test fun channelIndexGroupsAlternateUrlsAndNumbersEachPlaylistSeparately() {
        val channels = listOf(
            Channel("One", "https://a/1", "News", source = "Playlist A"),
            Channel("One", "https://a/1-alt", "News", source = "Playlist A"),
            Channel("Two", "https://a/2", "News", source = "Playlist A"),
            Channel("One", "https://b/1", "News", source = "Playlist B"),
            Channel("Film", "https://a/film.mp4", "Movies", source = "Playlist A", mediaType = "movie")
        )
        val index = HomeTvPolicies.buildChannelIndex(channels)
        val oneA = HomeTvPolicies.channelKey(channels[0])
        val twoA = HomeTvPolicies.channelKey(channels[2])
        val oneB = HomeTvPolicies.channelKey(channels[3])
        assertEquals(listOf(0, 1), index.indicesByKey[oneA])
        assertEquals(listOf(0, 2, 3, 4), index.representatives)
        assertEquals(listOf(0, 2, 3), index.liveRepresentatives)
        assertEquals(1, index.perPlaylistNumbers[oneA])
        assertEquals(2, index.perPlaylistNumbers[twoA])
        assertEquals(1, index.perPlaylistNumbers[oneB])
        assertEquals(1, index.continuousNumbers[oneA])
        assertEquals(2, index.continuousNumbers[twoA])
        assertEquals(3, index.continuousNumbers[oneB])
    }

    @Test fun channelIndexHandlesTwentyThousandRowsInOnePass() {
        val channels = buildList {
            repeat(10_000) { i ->
                add(Channel("Channel $i", "https://stream.example/$i.m3u8", "Large", source = "Big"))
                add(Channel("Channel $i", "https://backup.example/$i.m3u8", "Large", source = "Big"))
            }
        }
        val index = HomeTvPolicies.buildChannelIndex(channels)
        assertEquals(10_000, index.representatives.size)
        assertEquals(10_000, index.liveRepresentatives.size)
        assertEquals(10_000, index.perPlaylistNumbers.size)
        assertEquals(10_000, index.continuousNumbers.size)
        assertEquals(2, index.indicesByKey[HomeTvPolicies.channelKey(channels[0])]!!.size)
        assertEquals(10_000, index.perPlaylistNumbers[HomeTvPolicies.channelKey(channels.last())])
    }

    @Test fun libVlcAutoAndBestAvailableKeepTheOriginalAdaptiveResolutionRange() {
        assertEquals(listOf(":adaptive-logic=highest"), HomeTvPolicies.libVlcAdaptiveOptions("Auto"))
        assertEquals(listOf(":adaptive-logic=highest"), HomeTvPolicies.libVlcAdaptiveOptions("Best Available"))
        assertFalse(HomeTvPolicies.libVlcAdaptiveOptions("Auto").any { it.startsWith(":adaptive-maxheight=") })
    }

    @Test fun libVlcOnlyCapsResolutionWhenTheUserSelectsAnExplicitPreset() {
        assertEquals(
            listOf(":adaptive-maxheight=720", ":adaptive-logic=highest"),
            HomeTvPolicies.libVlcAdaptiveOptions("HD (720p)")
        )
        assertEquals(
            listOf(":adaptive-maxheight=1080", ":adaptive-logic=highest"),
            HomeTvPolicies.libVlcAdaptiveOptions("Full HD (1080p)")
        )
    }

    @Test fun libVlcNativePictureModeDoesNotInstallAnAdjustmentFilter() {
        assertTrue(HomeTvPolicies.libVlcPictureOptions("Standard",1f,1f,1f).isEmpty())
        assertEquals(
            listOf("--video-filter=adjust","--brightness=1.1","--contrast=1.2","--saturation=0.9"),
            HomeTvPolicies.libVlcPictureOptions("Cinema",1.1f,1.2f,0.9f)
        )
    }

    @Test fun libVlcVideoConfirmationRequiresARealPresentedFrame() {
        assertFalse(HomeTvPolicies.shouldConfirmVlcPlayback(true,false,true,5_000L))
        assertTrue(HomeTvPolicies.shouldConfirmVlcPlayback(true,true,true,5_000L))
        assertFalse(HomeTvPolicies.shouldConfirmVlcPlayback(false,false,false,5_000L))
        assertFalse(HomeTvPolicies.shouldConfirmVlcPlayback(false,false,true,1_000L))
        assertTrue(HomeTvPolicies.shouldConfirmVlcPlayback(false,false,true,1_500L))
    }

    @Test fun videoFreezePolicyUsesPresentedFramesAndIgnoresPausedPlayback() {
        assertFalse(HomeTvPolicies.isVideoOutputStalled(false,true,30_000L,30_000L))
        assertFalse(HomeTvPolicies.isVideoOutputStalled(true,true,5_000L,30_000L))
        assertTrue(HomeTvPolicies.isVideoOutputStalled(true,true,20_000L,0L))
        assertTrue(HomeTvPolicies.isVideoOutputStalled(true,false,0L,20_000L))
        assertFalse(HomeTvPolicies.isVideoOutputStalled(true,false,30_000L,5_000L))
    }

    @Test fun liveStreamsReceiveEnoughTimeToStabiliseBeforeEngineFallback() {
        assertEquals(30_000L, HomeTvPolicies.startupTimeoutMs("live", "file"))
        assertEquals(30_000L, HomeTvPolicies.startupTimeoutMs("live", "hls"))
        assertEquals(25_000L, HomeTvPolicies.startupTimeoutMs("movie", "hls"))
        assertEquals(15_000L, HomeTvPolicies.startupTimeoutMs("movie", "file"))
    }

    @Test fun youtubeNativeAttemptAndFallbackAreGuarded() {
        assertTrue(HomeTvPolicies.shouldAttemptNativeYoutube(true, false, false, false))
        assertFalse(HomeTvPolicies.shouldAttemptNativeYoutube(false, false, false, false))
        assertFalse(HomeTvPolicies.shouldAttemptNativeYoutube(true, true, false, false))
        assertFalse(HomeTvPolicies.shouldAttemptNativeYoutube(true, false, true, false))
        assertFalse(HomeTvPolicies.shouldAttemptNativeYoutube(true, false, false, true))
        assertTrue(HomeTvPolicies.shouldFallbackNativeYoutube(true, true, true))
        assertFalse(HomeTvPolicies.shouldFallbackNativeYoutube(false, true, true))
        assertFalse(HomeTvPolicies.shouldFallbackNativeYoutube(true, false, true))
        assertFalse(HomeTvPolicies.shouldFallbackNativeYoutube(true, true, false))
    }
}
