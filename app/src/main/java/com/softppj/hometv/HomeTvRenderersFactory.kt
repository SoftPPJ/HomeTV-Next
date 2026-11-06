package com.softppj.hometv

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

@OptIn(UnstableApi::class)
internal class HomeTvRenderersFactory(
    context:Context,
    private val channelGainProcessor:ChannelGainAudioProcessor
):DefaultRenderersFactory(context){
    override fun buildAudioSink(
        context:Context,
        enableFloatOutput:Boolean,
        enableAudioOutputPlaybackParams:Boolean
    ):AudioSink?{
        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setAudioProcessors(arrayOf(channelGainProcessor))
            .build()
    }
}
