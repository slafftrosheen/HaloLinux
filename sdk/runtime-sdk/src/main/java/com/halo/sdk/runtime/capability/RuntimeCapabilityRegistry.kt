package com.halo.sdk.runtime.capability

enum class RuntimeCapabilityId { LOCAL_VISION, SEMANTIC_MEMORY, SMART_BARGE_IN, AUDIO_TRANSCRIPTION_MODEL }
enum class RuntimeCapabilityState { UNAVAILABLE, AVAILABLE, INSTALL_REQUIRED, INSTALLING, INSTALLED, QUALIFICATION_REQUIRED, READY, FAILED }
data class RuntimeArtifactRef(val id: String, val fingerprint: String?, val bytes: Long? = null)
data class RuntimeCapabilityDescriptor(
    val id: RuntimeCapabilityId, val displayName: String, val description: String,
    val requiredInputCapabilities: Set<String> = emptySet(),
    val artifactRefs: List<RuntimeArtifactRef> = emptyList(), val optional: Boolean = true,
)
data class RuntimeCapabilityEvidence(
    val configured: Boolean = false, val artifactAvailable: Boolean = false,
    val installed: Boolean = false, val installing: Boolean = false,
    val runtimeAvailable: Boolean = false, val capabilityCompatible: Boolean = false,
    val qualified: Boolean = false, val failed: Boolean = false,
    val qualificationKey: String? = null,
)
data class RuntimeCapabilityStatus(val descriptor: RuntimeCapabilityDescriptor, val state: RuntimeCapabilityState, val evidence: RuntimeCapabilityEvidence)

/** Evidence is supplied by host adapters. A class existing in source never constitutes readiness. */
class RuntimeCapabilityRegistry {
    fun resolve(descriptor: RuntimeCapabilityDescriptor, evidence: RuntimeCapabilityEvidence): RuntimeCapabilityStatus {
        val state = when {
            evidence.failed -> RuntimeCapabilityState.FAILED
            evidence.installing -> RuntimeCapabilityState.INSTALLING
            !evidence.configured -> if (evidence.artifactAvailable) RuntimeCapabilityState.AVAILABLE else RuntimeCapabilityState.UNAVAILABLE
            !evidence.installed -> if (evidence.artifactAvailable) RuntimeCapabilityState.INSTALL_REQUIRED else RuntimeCapabilityState.UNAVAILABLE
            !evidence.runtimeAvailable || !evidence.capabilityCompatible -> RuntimeCapabilityState.INSTALLED
            !evidence.qualified || evidence.qualificationKey == null -> RuntimeCapabilityState.QUALIFICATION_REQUIRED
            else -> RuntimeCapabilityState.READY
        }
        return RuntimeCapabilityStatus(descriptor, state, evidence)
    }

    companion object {
        val descriptors = listOf(
            RuntimeCapabilityDescriptor(RuntimeCapabilityId.LOCAL_VISION, "Vision", "Read and describe images locally.", setOf("IMAGE")),
            RuntimeCapabilityDescriptor(RuntimeCapabilityId.SEMANTIC_MEMORY, "Semantic memory", "Find saved facts by meaning."),
            RuntimeCapabilityDescriptor(RuntimeCapabilityId.SMART_BARGE_IN, "Smart interruption", "Distinguish interruptions from backchannels."),
            RuntimeCapabilityDescriptor(RuntimeCapabilityId.AUDIO_TRANSCRIPTION_MODEL, "Audio transcription", "Experimental transcription first; dedicated STT remains the default.", setOf("AUDIO")),
        )
    }
}
