package com.halo.sdk.provider

import kotlinx.coroutines.flow.Flow

/** Publisher metadata is not installation, native compatibility or audible acceptance. */
data class SpeechPackVoice(val id: String, val label: String, val languages: Set<String>)
data class SpeechModelPack(
    val id: String,
    val providerId: String,
    val publisher: String,
    val family: String,
    val version: String,
    val languages: Set<String>,
    val voices: List<SpeechPackVoice>,
    val format: String,
    val quantization: String,
    val requiredFiles: Set<String>,
    val runtimeVersion: String,
    val archiveUrl: String? = null,
    val archiveSha256: String? = null,
    val archiveBytes: Long? = null,
    val estimatedWorkingBytes: Long,
    val backends: Set<String> = setOf("CPU"),
    val threadRange: IntRange = 1..4,
    val sentenceDelivery: Boolean = true,
    val experimental: Boolean = false,
    val sourceRevision: String? = null,
)

data class InstalledSpeechPack(
    val fingerprint: String,
    val modelId: String?,
    val complete: Boolean,
    val contentVerified: Boolean,
    val selected: Boolean,
    val nativeCompatibility: String = "UNKNOWN",
    val physicalQualification: String = "UNKNOWN",
)

/** Completed phrases, not Sherpa callback samples. Collection supplies backpressure. */
interface SegmentedPcmSpeechProvider : PcmSpeechSynthesisProvider {
    fun streamPcm(request: SpeechRequest): Flow<PcmSpeech>
    /** Capture pack/voice/settings once before the first segment of a response. */
    fun pinSpeechRequest(request: SpeechRequest): SpeechRequest = request
}
