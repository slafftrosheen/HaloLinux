package com.halo.sdk.provider

/**
 * Recognizes provider responses that mean "this request exceeded the model's context window".
 *
 * OpenAI-compatible APIs report context overflow as an ordinary `400`, so without an explicit
 * classification it collapses into a generic malformed-request failure. The distinction matters
 * because it is the one `400` that is actionable rather than a bug: the same request can succeed
 * after the conversation is compacted or a larger-context model is selected. Providers normally
 * also echo a machine-readable `code`, which is authoritative; free prose is only consulted as a
 * bounded, case-insensitive phrase set so unrelated 400s cannot be mislabelled.
 *
 * This is the single owner of that judgement: providers map their own wire response through it
 * rather than each inventing their own string checks.
 */
object ContextOverflow {

    /** Stable machine codes used by OpenAI-compatible servers, vLLM, llama.cpp and Ollama. */
    private val CODES = setOf(
        "context_length_exceeded",
        "context_window_exceeded",
        "model_context_window_exceeded",
        "maximum_context_length_exceeded",
        "max_tokens_exceeded",
        "context_overflow",
        "input_too_long",
        "prompt_too_long",
    )

    /**
     * Prose fragments that only ever appear in a context-overflow message. Deliberately narrow:
     * a bare "context" or "token" mention must not match, because those words occur in unrelated
     * routing and entitlement errors.
     */
    private val PHRASES = listOf(
        "maximum context length",
        "context length exceeded",
        "context window exceeded",
        "exceeds the context",
        "too many tokens",
        "exceeds the maximum number of tokens",
        "reduce the length of the messages",
        "prompt is too long",
        "input is too long",
        "message is too long",
        "token limit",
    )

    /**
     * @param providerCode machine code reported by the provider, e.g. `context_length_exceeded`.
     * @param providerMessage provider-supplied human text, used only as a fallback signal.
     */
    fun matches(providerCode: String?, providerMessage: String?): Boolean {
        val code = providerCode?.trim()?.lowercase()?.replace('-', '_')?.trim('_')
        if (!code.isNullOrEmpty() && code in CODES) return true
        val message = providerMessage?.lowercase() ?: return false
        return PHRASES.any { message.contains(it) }
    }
}
