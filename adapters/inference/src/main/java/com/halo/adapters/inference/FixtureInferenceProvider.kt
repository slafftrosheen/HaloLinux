package com.halo.adapters.inference

import com.halo.libraries.common.core.Identifier
import com.halo.sdk.provider.AiCapability
import com.halo.sdk.provider.AiContext
import com.halo.sdk.provider.AiInferenceProvider
import com.halo.sdk.provider.AiModel
import com.halo.sdk.provider.AiProviderException
import com.halo.sdk.provider.AiRequest
import com.halo.sdk.provider.AiResponse
import com.halo.sdk.provider.AiStreamEvent
import com.halo.sdk.provider.AiExecutionLocation
import com.halo.sdk.provider.ProviderAuthInfo
import com.halo.sdk.provider.ProviderAuthKind
import com.halo.sdk.provider.ProviderAuthState
import com.halo.sdk.provider.ProviderConfigurationDescriptor
import com.halo.sdk.provider.ProviderConfigurationField
import com.halo.sdk.provider.ProviderConfigurationReporting
import com.halo.sdk.provider.ProviderFieldKind
import com.halo.sdk.provider.ProviderStatus
import com.halo.sdk.provider.ProviderStatus.State
import com.halo.sdk.provider.ProviderReadiness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Deterministic fixture provider for automated testing.
 * 
 * This provider returns predictable responses without any network calls,
 * making tests fast, reliable, and independent of external services.
 */
class FixtureInferenceProvider(
    private val config: FixtureProviderConfig,
) : AiInferenceProvider, ProviderConfigurationReporting {

    override val configurationDescriptor: ProviderConfigurationDescriptor
        get() = ProviderConfigurationDescriptor(
            config.id,
            config.displayName,
            AiExecutionLocation.LOCAL,
            emptyList(),
            "fixture",
            com.halo.sdk.provider.ProviderAccessKind.LOCAL.name,
        )

    override val id: String get() = config.id
    override val displayName: String get() = config.displayName
    override val isLocal: Boolean = true

    private val _status = MutableStateFlow(config.initialStatus)
    override val status: StateFlow<ProviderStatus> = _status.asStateFlow()

    private val _authInfo = MutableStateFlow(ProviderAuthInfo(
        authKind = ProviderAuthKind.NONE,
        authState = ProviderAuthState.CONNECTED,
    ))
    val authInfo: StateFlow<ProviderAuthInfo> = _authInfo.asStateFlow()

    override fun configurationChanged() {
        // Fixture provider has no configuration to change
    }

    override suspend fun getCapabilities(): Set<AiCapability> = config.capabilities

    override suspend fun getStatus(): ProviderStatus = _status.value

    override suspend fun testConnection(): Boolean = true

    override suspend fun generate(request: AiRequest, context: AiContext): AiResponse {
        val response = config.responses[request.modelId ?: "default"] 
            ?: config.defaultResponse
            ?: "Fixture response for: ${request.modelId ?: "default"}"
        
        return AiResponse(
            text = response,
            providerId = id,
            modelId = request.modelId,
        )
    }

    override fun stream(request: AiRequest, context: AiContext): Flow<AiStreamEvent> = flow {
        val response = config.responses[request.modelId ?: "default"] 
            ?: config.defaultResponse
            ?: "Fixture response for: ${request.modelId ?: "default"}"
        
        // Emit as a single chunk for simplicity
        emit(AiStreamEvent.TextDelta(response))
        emit(AiStreamEvent.Done)
    }

    override suspend fun listModels(): List<AiModel> = withContext(Dispatchers.IO) {
        config.models.ifEmpty {
            listOf(
                AiModel(
                    id = "fixture-model",
                    displayName = "Fixture Model",
                    providerId = id,
                    inputCapabilities = setOf(AiCapability.TEXT),
                    contextLength = 4096,
                    supportsStreaming = true,
                    supportsTools = false,
                )
            )
        }
    }
}

/**
 * Configuration for the fixture provider.
 */
data class FixtureProviderConfig(
    val id: String = "fixture",
    val displayName: String = "Fixture Provider",
    val initialStatus: ProviderStatus = ProviderStatus(
        ProviderStatus.State.READY,
        "Fixture provider ready",
        ProviderReadiness.Ready,
    ),
    val capabilities: Set<AiCapability> = setOf(AiCapability.TEXT, AiCapability.STREAMING),
    val models: List<AiModel> = emptyList(),
    val responses: Map<String, String> = emptyMap(),
    val defaultResponse: String? = null,
)