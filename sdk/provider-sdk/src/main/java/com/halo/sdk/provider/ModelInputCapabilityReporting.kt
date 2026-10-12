package com.halo.sdk.provider

/** Artifact-scoped execution support, distinct from the provider's aggregate input support. */
interface ModelInputCapabilityReporting {
    suspend fun capabilitiesForModel(modelId: String): Set<AiCapability>
}
