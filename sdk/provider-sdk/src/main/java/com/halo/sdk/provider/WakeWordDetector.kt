package com.halo.sdk.provider

import com.halo.sdk.host.AudioFrame

data class WakeWordResult(
    val detected: Boolean,
    val confidence: Float = 0f,
    val startIndex: Int = -1,
    val endIndex: Int = -1
)

interface WakeWordDetector {
    val metadata: ProviderMetadata
        get() = ProviderMetadata(id = this::class.java.name, supportsStreaming = false)

    /**
     * Whether the detector should only receive frames that have already passed VAD.
     * Neural keyword spotters generally need the complete audio stream, including
     * low-energy frames, so they can own their own temporal context.
     */
    val requiresSpeechGate: Boolean
        get() = true

    fun process(frame: AudioFrame): WakeWordResult
    suspend fun health(): Boolean
    suspend fun healthDetails(): ProviderHealth = ProviderHealth(healthy = health())
    suspend fun close() = Unit
}
