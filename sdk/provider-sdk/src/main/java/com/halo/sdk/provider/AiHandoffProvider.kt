package com.halo.sdk.provider

/**
 * Optional capability for providers that hand work off to an external assistant.
 *
 * Handoff is intentionally separate from normal inference: callers must not
 * assume a response channel, prompt injection support, or round-trip execution.
 */
interface AiHandoffProvider : AiProvider {
    fun isAvailable(): Boolean
    fun assistantPackageName(): String?
    fun handoff(prompt: String? = null)
}
