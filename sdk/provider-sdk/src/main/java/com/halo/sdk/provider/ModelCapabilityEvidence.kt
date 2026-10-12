package com.halo.sdk.provider

enum class CapabilityEvidence { AUTHORITATIVE_METADATA, HALO_PROBE_SUCCESS, CATALOG_PIN, HEURISTIC, UNKNOWN }
data class ModelCapabilityEvidence(val providerId: String, val modelId: String, val capability: AiCapability,
    val evidence: CapabilityEvidence, val verifiedAtMs: Long? = null)

fun AiModel.hasVerifiedImageInput(): Boolean = AiCapability.IMAGE in inputCapabilities &&
    id != "openrouter/free" && when (capabilityEvidence[AiCapability.IMAGE]) {
        CapabilityEvidence.AUTHORITATIVE_METADATA, CapabilityEvidence.HALO_PROBE_SUCCESS -> true
        CapabilityEvidence.CATALOG_PIN -> executionLocation == AiExecutionLocation.ON_DEVICE
        else -> false
    }
