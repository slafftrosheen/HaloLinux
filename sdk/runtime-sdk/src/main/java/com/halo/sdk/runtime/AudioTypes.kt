package com.halo.sdk.runtime

/**
 * Audio device type classification.
 */
enum class AudioDeviceType {
    INPUT,
    OUTPUT,
    DUPLEX
}

/**
 * Audio sample format specification.
 */
enum class AudioSampleFormat {
    PCM_8BIT,
    PCM_16BIT,
    PCM_24BIT,
    PCM_32BIT,
    FLOAT_32BIT,
    FLOAT_64BIT,
    UNKNOWN
}

/**
 * Audio device information.
 */
data class AudioDevice(
    val id: String,
    val name: String,
    val deviceType: AudioDeviceType,
    val sampleRates: List<Int>,
    val formats: List<AudioSampleFormat>,
    val channels: Int,
    val isDefault: Boolean,
    val isAvailable: Boolean,
)

/**
 * Audio context for capture/playback configuration.
 */
data class AudioContext(
    val sampleRate: Int = 48000,
    val channels: Int = 2,
    val format: AudioSampleFormat = AudioSampleFormat.PCM_16BIT,
    val bufferSize: Int = 4096,
)