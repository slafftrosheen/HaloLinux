package com.halo.sdk.provider

/**
 * Typed view of provider capabilities exposed to the cognitive runtime.
 *
 * Inference and external-assistant handoff are separate contracts and should
 * never be inferred from implementation-specific provider IDs.
 */
data class AiProviderRegistry(
    val inference: List<AiInferenceProvider>,
    val handoff: List<AiHandoffProvider>
) {
    val all: List<AiProvider>
        get() = inference + handoff

    companion object {
        fun from(providers: List<AiProvider>): AiProviderRegistry {
            val distinct = providers.distinctBy { it.id }
            return AiProviderRegistry(
                inference = distinct.filterIsInstance<AiInferenceProvider>().filterNot { it is AiHandoffProvider },
                handoff = distinct.filterIsInstance<AiHandoffProvider>()
            )
        }
    }
}
