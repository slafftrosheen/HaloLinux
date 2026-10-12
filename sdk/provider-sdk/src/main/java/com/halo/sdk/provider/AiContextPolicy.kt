package com.halo.sdk.provider

/**
 * Policy for controlling what data is included in the AI context.
 */
data class AiContextPolicy(
    val includeConversation: Boolean = true,
    val includeDeviceState: Boolean = true,
    val includeRecentEvents: Boolean = false,
    val includeMemories: Boolean = false,
    val includeAvailableTools: Boolean = true,
    val includeLocation: Boolean = false,
    val includeImages: Boolean = true,
    val includeAudio: Boolean = false,
    val includeVideo: Boolean = false,
    val includeWorkspace: Boolean = true,
    val allowCloudWorkspace: Boolean = false,
    val allowCloudScreenContext: Boolean = false,
    val allowCloudImages: Boolean = false,
    val allowCloudMemories: Boolean = false,
) {
    companion object {
        val CONSERVATIVE = AiContextPolicy(
            includeConversation = true,
            includeDeviceState = true,
            includeRecentEvents = false,
            includeMemories = false,
            includeAvailableTools = true,
        )
    }
}
