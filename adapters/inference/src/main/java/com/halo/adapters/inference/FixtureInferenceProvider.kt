package com.halo.adapters.inference

import com.halo.sdk.provider.AiCapability
import com.halo.sdk.provider.AiContext
import com.halo.sdk.provider.AiExecutionLocation
import com.halo.sdk.provider.AiInferenceProvider
import com.halo.sdk.provider.AiModel
import com.halo.sdk.provider.AiRequest
import com.halo.sdk.provider.AiResponse
import com.halo.sdk.provider.AiStreamEvent
import com.halo.sdk.provider.ProviderReadiness
import com.halo.sdk.provider.ProviderStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/** Explicitly test-only provider. Never select silently in production. */
class FixtureInferenceProvider(
    private val config: FixtureProviderConfig = FixtureProviderConfig(),
) : AiInferenceProvider {
    override val id: String get() = config.id
    override val displayName: String get() = config.displayName
    override val isLocal: Boolean = true
    override val status: StateFlow<ProviderStatus> = MutableStateFlow(
        ProviderStatus(ProviderStatus.State.READY, "Fixture ready", ProviderReadiness.Ready)
    )

    override suspend fun getCapabilities(): Set<AiCapability> = setOf(AiCapability.TEXT, AiCapability.STREAMING)
    override suspend fun testConnection(): Boolean = true

    override suspend fun generate(request: AiRequest, context: AiContext): AiResponse = AiResponse(
        text = config.responses[request.modelId] ?: config.defaultResponse,
        providerId = id,
        modelId = request.modelId,
    )

    override fun stream(request: AiRequest, context: AiContext): Flow<AiStreamEvent> = flow {
        emit(AiStreamEvent.Started)
        val response = generate(request, context)
        emit(AiStreamEvent.Token(response.text))
        emit(AiStreamEvent.Completed(response))
    }

    override suspend fun listModels(): List<AiModel> = listOf(
        AiModel(
            id = "fixture-model", displayName = "Fixture Model", providerId = id,
            inputCapabilities = setOf(AiCapability.TEXT),
            contextLength = 4096L, supportsStreaming = true, supportsTools = false,
            executionLocation = AiExecutionLocation.ON_DEVICE,
        )
    )
}

data class FixtureProviderConfig(
    val id: String = "fixture",
    val displayName: String = "Fixture provider",
    val defaultResponse: String = "Fixture response",
    val responses: Map<String, String> = emptyMap(),
)
