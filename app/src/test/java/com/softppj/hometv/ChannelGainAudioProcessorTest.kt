package com.softppj.hometv

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ChannelGainAudioProcessorTest {
    private fun process(gain:Float,vararg samples:Short):List<Short>{
        val processor=ChannelGainAudioProcessor(gain)
        processor.configure(AudioProcessor.AudioFormat(48000,1,C.ENCODING_PCM_16BIT))
        processor.flush()
        val input=ByteBuffer.allocateDirect(samples.size*2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach{input.putShort(it)}
        input.flip()
        processor.queueInput(input)
        val output=processor.output.order(ByteOrder.LITTLE_ENDIAN)
        val result=mutableListOf<Short>()
        while(output.remaining()>=2)result.add(output.short)
        return result
    }

    @Test fun appliesLowDefaultAndBoostGainToPcmSamples(){
        assertEquals(listOf(200.toShort(),(-200).toShort()),process(0.2f,1000,-1000))
        assertEquals(listOf(2000.toShort(),(-2000).toShort()),process(2f,1000,-1000))
    }

    @Test fun clampsBoostedSamplesWithoutWrapping(){
        assertEquals(listOf(Short.MAX_VALUE,Short.MIN_VALUE),process(2f,30000,-30000))
    }

    @Test fun zeroGainMutesOnlyTheSelectedChannelOutput(){
        assertEquals(listOf(0.toShort(),0.toShort()),process(0f,1234,-5678))
    }
}
