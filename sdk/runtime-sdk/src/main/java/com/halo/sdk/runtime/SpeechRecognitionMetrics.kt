package com.halo.sdk.runtime

/** Execution provenance, separate from evidence used by the transcript gate. */
data class SpeechRecognitionMetrics(
    val providerId: String? = null,
    val modelId: String? = null,
    val modelDisplayName: String? = null,
    val language: String? = null,
    val loadMs: Long? = null,
    val decodeMs: Long? = null,
    val totalMs: Long? = null,
    val audioDurationMs: Long? = null
) {
    val realTimeFactor: Double? get() = audioDurationMs?.takeIf { it > 0 }?.let { duration -> decodeMs?.toDouble()?.div(duration) }
}

enum class SpeechDiagnosticStage { CAPTURE, PREPROCESS, VAD, MODEL_RESOLVE, RESOURCE_ACQUIRE, MODEL_LOAD, DECODE, RESULT, TRANSCRIPT_GATE }
class SpeechDiagnosticException(val stage: SpeechDiagnosticStage, cause: Throwable) : Exception("${stage.name}: ${cause.message}", cause)
