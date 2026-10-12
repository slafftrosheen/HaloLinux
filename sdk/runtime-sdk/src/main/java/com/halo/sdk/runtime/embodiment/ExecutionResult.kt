package com.halo.sdk.runtime.embodiment

/**
 * Represents the correlated outcome or state transition of a physical execution attempt.
 */
data class ExecutionResult(
    /** The unique intent ID of the intent being executed. */
    val intentId: String,
    
    /** The current state in the execution lifecycle. */
    val status: ExecutionStatus,
    
    /** Semantic reason for the status transition (particularly for DENIED, REJECTED, FAILED). */
    val reason: String? = null,
    
    /** Time the event occurred on the embodiment. */
    val timestamp: Long,
    
    /** The stable identity of the hardware/simulated embodiment. */
    val embodimentId: String,
    
    /** Optional key-value diagnostics from the hardware. */
    val metadata: Map<String, String> = emptyMap()
)
