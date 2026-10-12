package com.halo.sdk.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

data class ProviderMetadata(
    val id: String,
    val displayName: String = id,
    val local: Boolean = true,
    val supportsStreaming: Boolean = true
)

data class ProviderHealth(
    val healthy: Boolean,
    val message: String? = null
)

/**
 * Legacy inference contract retained only as a source-compatibility bridge.
 *
 * New runtime providers MUST implement [AiInferenceProvider] directly. In
 * particular, do not use this interface for runtime service registration or
 * provider routing: it predates the split between inference and assistant
 * handoff and therefore cannot express that boundary correctly.
 */
@Deprecated(
    message = "Use AiInferenceProvider. InferenceProvider is a legacy compatibility bridge.",
    replaceWith = ReplaceWith("AiInferenceProvider")
)
interface InferenceProvider : AiProvider {
    val metadata: ProviderMetadata
        get() = ProviderMetadata(id = this::class.java.name)

    override val id: String get() = metadata.id
    override val displayName: String get() = metadata.displayName
    override val isLocal: Boolean get() = metadata.local

    override suspend fun getStatus(): ProviderStatus {
        return if (health()) ProviderStatus.READY else ProviderStatus.UNAVAILABLE
    }

    override suspend fun getCapabilities(): Set<AiCapability> {
        val caps = mutableSetOf(AiCapability.TEXT)
        if (metadata.supportsStreaming) caps.add(AiCapability.STREAMING)
        return caps
    }

    suspend fun generate(request: AiRequest, context: AiContext): AiResponse {
        val conversation = AIConversation(
            id = "legacy",
            messages = context.conversation.toMutableList()
        )
        val legacyRequest = AIRequestLegacy(conversation)
        val legacyResponse = complete(legacyRequest)
        return AiResponse(text = legacyResponse.text, providerId = id)
    }

    fun stream(request: AiRequest, context: AiContext): Flow<AiStreamEvent> = emptyFlow()

    suspend fun complete(request: AIRequestLegacy): AIResponseLegacy
    fun stream(prompt: String): Flow<String>
    suspend fun health(): Boolean

    suspend fun healthDetails(): ProviderHealth = ProviderHealth(healthy = health())

    suspend fun close() = Unit
}
