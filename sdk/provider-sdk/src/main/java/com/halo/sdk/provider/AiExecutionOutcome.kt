package com.halo.sdk.provider

/**
 * Typed terminal outcomes for provider execution.
 *
 * These outcomes belong to the runtime contract rather than being encoded in
 * AiResponse.text. This prevents control flow from depending on magic strings.
 */
sealed interface AiExecutionOutcome {
    data class Response(val response: AiResponse) : AiExecutionOutcome

    data class HandoffStarted(
        val providerId: String,
        val modelId: String?,
        val assistantPackage: String? = null
    ) : AiExecutionOutcome
}
