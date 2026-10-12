package com.halo.sdk.provider

/**
 * Policy for routing AI reasoning requests.
 */
enum class AiRoutingPolicy {
    /** Only use the explicitly selected provider and model. Fail if unavailable. */
    EXPLICIT,
    
    /** Automatically choose the most capable/available provider. */
    AUTOMATIC,
    
    /** Exclusively use local providers (e.g. LiteRT-LM, Ollama). Fail explicitly if none are available. */
    LOCAL_ONLY,
    
    /** Use the preferred provider, but fall back to the next best if it fails. */
    PREFERRED_WITH_FALLBACK
}
