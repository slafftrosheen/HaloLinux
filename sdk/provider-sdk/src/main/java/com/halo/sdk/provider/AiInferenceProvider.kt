package com.halo.sdk.provider

import kotlinx.coroutines.flow.Flow

/**
 * Contract for providers that perform actual model inference.
 *
 * External assistant handoff is intentionally separate and is represented by
 * [AiHandoffProvider].
 */
interface AiInferenceProvider : AiProvider {
    val supportsTemperatureOverride: Boolean get() = false
    override suspend fun getCapabilities(): Set<AiCapability>
    suspend fun generate(request: AiRequest, context: AiContext): AiResponse
    fun stream(request: AiRequest, context: AiContext): Flow<AiStreamEvent>
    /**
     * Returns provider models without blocking the caller's dispatcher.
     * Implementations own dispatching for filesystem, network, DNS, native
     * metadata, and large parsing work, so callers may safely invoke this on Main.
     */
    suspend fun listModels(): List<AiModel>
    /** Explicit settings refresh may bypass a provider's inference-time metadata cache. */
    suspend fun refreshModels(): List<AiModel> = listModels()
}
