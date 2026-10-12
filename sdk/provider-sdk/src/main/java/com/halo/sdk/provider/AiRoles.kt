package com.halo.sdk.provider

enum class AiModelRole {
    CHAT,
    FAST,
    REASONING,
    AGENTIC,
    VISION
}

enum class AiRoleSelection {
    INHERIT_DEFAULT,
    AUTOMATIC,
    EXPLICIT,
    DISABLED
}

data class AiRoleBinding(
    val role: AiModelRole,
    val providerId: String?,
    val modelId: String?,
    val selection: AiRoleSelection
) {
    val hasExplicitTarget: Boolean get() = !providerId.isNullOrBlank() && !modelId.isNullOrBlank()
}
