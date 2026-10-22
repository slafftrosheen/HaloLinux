package com.halo.runtime.provider.response

import com.halo.sdk.provider.AiCapability
import com.halo.sdk.provider.AiErrorType
import com.halo.sdk.provider.AiProvider
import com.halo.sdk.provider.AiProviderException
import com.halo.sdk.provider.AiResponse

/**
 * Shared validation at the provider-execution boundary.
 *
 * It is intentionally outside cognition: both routing adapters and cognitive
 * orchestration must enforce exactly the same response identity invariants.
 */
object AiResponseValidator {

    fun validate(
        provider: AiProvider,
        capabilities: Set<AiCapability>,
        expectedModelId: String,
        response: AiResponse
    ): AiResponse {
        if (response.providerId.isNotBlank() && response.providerId != provider.id) {
            throw AiProviderException(
                AiErrorType.INVALID_RESPONSE,
                "Provider identity mismatch: expected=${provider.id}, actual=${response.providerId}"
            )
        }

        if (response.toolCalls.isNotEmpty() && !capabilities.contains(AiCapability.TOOLS)) {
            throw AiProviderException(
                AiErrorType.INVALID_RESPONSE,
                "Provider returned tool calls without TOOLS capability"
            )
        }

        if (response.text.isBlank() && response.toolCalls.isEmpty()) {
            throw AiProviderException(
                AiErrorType.EMPTY_RESPONSE,
                "Provider returned no usable response"
            )
        }

        val requestedModelId = response.requestedModelId ?: response.modelId
        if (requestedModelId != null && requestedModelId != expectedModelId) {
            throw AiProviderException(
                AiErrorType.INVALID_RESPONSE,
                "Model identity mismatch: expected=$expectedModelId, actual=$requestedModelId"
            )
        }

        return response.copy(
            providerId = provider.id,
            modelId = expectedModelId,
            requestedModelId = expectedModelId,
            resolvedModelId = response.resolvedModelId ?: expectedModelId,
        )
    }
}
