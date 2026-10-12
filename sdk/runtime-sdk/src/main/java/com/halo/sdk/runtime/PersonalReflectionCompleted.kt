package com.halo.sdk.runtime

/** Auxiliary REFLECTION receipt; never a root trace stage or a change to action truth. */
data class PersonalReflectionCompleted(
    override val id: String,
    val runId: String,
    val episodeId: String,
    val status: String,
    val mode: String,
    val usefulness: String,
    val proposalCount: Int,
    val procedureSuggested: Boolean,
    val automationSuggested: Boolean,
    val stage: RuntimeStage = RuntimeStage.REFLECTION,
    override val timestamp: Long = System.currentTimeMillis(),
) : HaloEvent
