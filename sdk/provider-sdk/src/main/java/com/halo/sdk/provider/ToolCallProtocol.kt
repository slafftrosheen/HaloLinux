package com.halo.sdk.provider

/**
 * AGENTIC-001 how a provider/model combination can participate in a Halo tool loop.
 *
 * Two facts that were previously conflated:
 *
 * - [NATIVE] the runtime emits real tool-call objects (LiteRT `ToolCall`, OpenAI
 *   `tool_calls`, ...). This is what the *provider adapter* implements.
 * - [HALO_PROMPT] the model cannot emit tool calls, but it can follow an instruction
 *   to answer with one bounded JSON envelope that Halo parses, validates against the
 *   ToolRegistry schema and executes itself. Halo remains the executor.
 *
 * The router cares about "can Halo complete a tool loop with this model", which is
 * true for both. Only the adapter cares which of the two it is.
 */
enum class ToolCallProtocol {
    /** No tool loop is possible with the current model. */
    NONE,

    /** The runtime returns native tool calls. */
    NATIVE,

    /** Halo prompt-mediated envelope protocol at the provider adapter boundary. */
    HALO_PROMPT,
    ;

    /** Whether a Halo tool loop can be completed with this protocol. */
    val supportsToolLoop: Boolean
        get() = this != NONE
}

/**
 * Optional provider capability: report how a Halo tool loop would run against the
 * provider's currently selected/loaded model. Nothing may be claimed as [NATIVE]
 * without an observed native tool call for that artifact.
 */
interface ToolProtocolReporting {
    suspend fun toolCallProtocol(): ToolCallProtocol = ToolCallProtocol.NONE
    /** Exact routed model; legacy providers may retain their current-model implementation. */
    suspend fun toolCallProtocol(modelId: String): ToolCallProtocol = toolCallProtocol()
}
