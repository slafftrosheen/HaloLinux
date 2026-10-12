package com.halo.sdk.host

import com.halo.sdk.host.AudioBuffer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavUtils {

    /**
     * Writes an AudioBuffer to a standard WAV file.
     * Assumes all frames in the buffer have the same sample rate and channel count.
     */
    fun writeWavFile(file: File, buffer: AudioBuffer) {
        val frames = buffer.frames
        if (frames.isEmpty()) {
            file.writeBytes(ByteArray(0))
            return
        }

        val sampleRate = frames.first().sampleRate
        val channels = frames.first().channels
        
        var totalSamples = 0
        for (frame in frames) {
            totalSamples += frame.samples.size
        }

        // 16-bit PCM = 2 bytes per sample
        val pcmDataSize = totalSamples * 2
        val headerSize = 44
        val fileSize = headerSize + pcmDataSize

        val header = ByteArray(headerSize)
        val byteBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF Header
        byteBuffer.put("RIFF".toByteArray())
        byteBuffer.putInt(fileSize - 8)
        byteBuffer.put("WAVE".toByteArray())

        // Format chunk
        byteBuffer.put("fmt ".toByteArray())
        byteBuffer.putInt(16) // Format chunk size
        byteBuffer.putShort(1.toShort()) // Audio format (1 = PCM)
        byteBuffer.putShort(channels.toShort()) // Num channels
        byteBuffer.putInt(sampleRate) // Sample rate
        byteBuffer.putInt(sampleRate * channels * 2) // Byte rate
        byteBuffer.putShort((channels * 2).toShort()) // Block align
        byteBuffer.putShort(16.toShort()) // Bits per sample

        // Data chunk
        byteBuffer.put("data".toByteArray())
        byteBuffer.putInt(pcmDataSize)

        FileOutputStream(file).use { out ->
            out.write(header)
            
            // Write PCM data
            val pcmBytes = ByteArray(pcmDataSize)
            var offset = 0
            for (frame in frames) {
                for (sample in frame.samples) {
                    pcmBytes[offset++] = (sample.toInt() and 0xff).toByte()
                    pcmBytes[offset++] = ((sample.toInt() shr 8) and 0xff).toByte()
                }
            }
            out.write(pcmBytes)
        }
    }
}
