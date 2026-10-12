package com.halo.sdk.runtime

import java.util.UUID

data class ReasoningResult(
    val intent: HaloIntent,
    val confidence: Float,
    val explanation: String
)

data class ReasoningCompleted(
    val result: ReasoningResult,
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent
