package com.halo.sdk.provider

/** Exact-target readiness, independent of the provider's configured conversation default. */
interface ModelReadinessReporting {
    suspend fun statusForModel(modelId: String): ProviderStatus
}
