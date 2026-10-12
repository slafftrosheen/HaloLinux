package com.halo.sdk.provider

enum class AssistantTurnStatus {
    ACCEPTED,
    GENERATING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class AssistantTurn(
    val id: String,
    val userText: String,
    val providerId: String? = null,
    val modelId: String? = null,
    val status: AssistantTurnStatus,
    val responseText: String = "",
    val errorMessage: String? = null,
    val startedAtMs: Long,
    val completedAtMs: Long? = null
)
