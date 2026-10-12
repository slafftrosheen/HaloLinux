package com.halo.sdk.runtime.capability

/**
 * MINING-P2-001 optional capability packs.
 *
 * MINING-P1-001 defined semantic-memory interfaces but deliberately required no embedding model. This
 * is the product-facing half: a generic, honest description of an optional capability Halo could gain
 * by installing an artifact — never a mystery list of model filenames.
 *
 * Hard rule: Halo does not invent or download an unverified artifact. A capability whose artifact
 * metadata Halo has not verified reports [OptionalCapabilityState.NOT_AVAILABLE] and stays there.
 */
enum class OptionalCapabilityKind {
    /** Ordinary language models: they answer, reason and call tools. */
    LANGUAGE_MODEL,

    /** Capability models: they enable a specific ability and are not chat models. */
    CAPABILITY_MODEL,
}

enum class OptionalCapabilityState {
    /** Halo has no verified artifact for this capability. The honest default. */
    NOT_AVAILABLE,

    /** An artifact Halo verified exists and the user may choose to install it. */
    AVAILABLE_TO_INSTALL,
    INSTALLING,

    /** Installed but not yet proven usable on this device. */
    INSTALLED,

    /** Installed and proven usable. */
    READY,
    FAILED,
    ;

    val usable: Boolean get() = this == READY
}

data class OptionalRuntimeCapability(
    /** Stable id, e.g. "capability.semantic_memory". */
    val id: String,
    val kind: OptionalCapabilityKind,
    val displayName: String,
    /** Plain language: what does the user gain? Never an artifact filename. */
    val purpose: String,
    val state: OptionalCapabilityState = OptionalCapabilityState.NOT_AVAILABLE,
    /** Bounded reason/hint for the current state, shown as secondary copy. */
    val detail: String? = null,
) {
    val installed: Boolean
        get() = state == OptionalCapabilityState.INSTALLED || state == OptionalCapabilityState.READY

    /** Short label for a compact settings row. */
    val stateLabel: String
        get() = when (state) {
            OptionalCapabilityState.NOT_AVAILABLE -> "Not installed"
            OptionalCapabilityState.AVAILABLE_TO_INSTALL -> "Available to install"
            OptionalCapabilityState.INSTALLING -> "Installing"
            OptionalCapabilityState.INSTALLED -> "Installed"
            OptionalCapabilityState.READY -> "Ready"
            OptionalCapabilityState.FAILED -> "Failed"
        }
}

/**
 * What Halo can offer today, with no artifact metadata verified for any of them yet.
 *
 * This list is a truthful starting point, not a wish list presented as availability.
 */
object BuiltInOptionalCapabilities {
    const val SEMANTIC_MEMORY = "capability.semantic_memory"
    const val SMART_INTERRUPTION = "capability.smart_interruption"
    const val VISION_UNDERSTANDING = "capability.vision_understanding"
    const val AUDIO_UNDERSTANDING = "capability.audio_understanding"

    fun defaults(): List<OptionalRuntimeCapability> = listOf(
        OptionalRuntimeCapability(
            id = SEMANTIC_MEMORY,
            kind = OptionalCapabilityKind.CAPABILITY_MODEL,
            displayName = "Semantic memory",
            purpose = "Find saved facts by meaning, not only by matching words.",
            detail = "Lexical search already works; this only adds a meaning-based score.",
        ),
        OptionalRuntimeCapability(
            id = SMART_INTERRUPTION,
            kind = OptionalCapabilityKind.CAPABILITY_MODEL,
            displayName = "Smart interruption",
            purpose = "Tell a real interruption apart from a short \"mm-hm\" while Halo is speaking.",
            detail = "Physical controls always interrupt, with or without this.",
        ),
        OptionalRuntimeCapability(
            id = VISION_UNDERSTANDING,
            kind = OptionalCapabilityKind.CAPABILITY_MODEL,
            displayName = "Vision",
            purpose = "Describe and reason about camera or wearable images locally.",
        ),
        OptionalRuntimeCapability(
            id = AUDIO_UNDERSTANDING,
            kind = OptionalCapabilityKind.CAPABILITY_MODEL,
            displayName = "Audio understanding",
            purpose = "Transcribe with a multimodal model instead of the dedicated speech engine.",
            detail = "Sherpa stays the default; this is a future alternative, not a replacement.",
        ),
    )
}
