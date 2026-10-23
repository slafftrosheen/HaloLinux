package com.halo.sdk.provider

import com.halo.sdk.host.AudioFrame
import kotlinx.coroutines.flow.Flow

data class SpeechRequest(
    val text: String,
    val voiceId: String? = null,
    val language: String? = null,
    val modelId: String? = null,
    val voiceSettings: Map<String, Double> = emptyMap(),
    /** Actual playback ranges, emitted only from supplied PCM timing evidence. */
    val onPlaybackRange: ((Int, Int) -> Unit)? = null,
    val remoteProvenance: RemoteDispatchProvenance? = null,
)

enum class SpeechErrorCode { NOT_CONFIGURED, AUTHENTICATION_FAILED, RATE_LIMITED, NETWORK_ERROR, VOICE_NOT_FOUND, MODEL_NOT_AVAILABLE, SYNTHESIS_FAILED, INVALID_AUDIO_RESPONSE, CANCELLED }
class SpeechProviderException(val code: SpeechErrorCode, message: String = code.name) : Exception(message)

data class SpeechResult(
    val durationMillis: Long,
    val successful: Boolean
)

interface SpeechSynthesisProvider {
    val metadata: ProviderMetadata
        get() = ProviderMetadata(id = this::class.java.name, supportsStreaming = true)
    suspend fun synthesize(request: SpeechRequest): SpeechResult
    fun synthesizeWithListener(request: SpeechRequest, onStart: () -> Unit): suspend () -> SpeechResult = { synthesize(request) }
    fun stopCurrent() = Unit
    fun stream(text: String): Flow<AudioFrame>
    suspend fun health(): Boolean
    suspend fun healthDetails(): ProviderHealth = ProviderHealth(healthy = health())
    suspend fun close() = Unit
}
