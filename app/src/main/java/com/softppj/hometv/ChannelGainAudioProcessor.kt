package com.softppj.hometv

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

internal class ChannelGainAudioProcessor(initialGain:Float = 1f):BaseAudioProcessor(){
    @Volatile var gain:Float=initialGain.coerceIn(0f,2f)
    private var encoding:Int=C.ENCODING_PCM_16BIT

    override fun onConfigure(inputAudioFormat:AudioProcessor.AudioFormat):AudioProcessor.AudioFormat{
        encoding=inputAudioFormat.encoding
        return if(encoding==C.ENCODING_PCM_16BIT || encoding==C.ENCODING_PCM_FLOAT)
            inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer:ByteBuffer){
        val input=inputBuffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        val output=replaceOutputBuffer(input.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        val currentGain=gain.coerceIn(0f,2f)
        when(encoding){
            C.ENCODING_PCM_16BIT->{
                while(input.remaining()>=2){
                    val sample=input.short.toInt()
                    val amplified=(sample*currentGain).roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(),Short.MAX_VALUE.toInt())
                    output.putShort(amplified.toShort())
                }
            }
            C.ENCODING_PCM_FLOAT->{
                while(input.remaining()>=4){
                    val sample=input.float
                    output.putFloat((sample*currentGain).coerceIn(-1f,1f))
                }
            }
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }
}
