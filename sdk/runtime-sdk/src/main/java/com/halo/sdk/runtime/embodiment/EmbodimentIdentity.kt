package com.halo.sdk.runtime.embodiment

import java.time.Instant

enum class EmbodimentType {
    ROBOT,
    WEARABLE_GLASSES,
    UNKNOWN
}

data class EmbodimentIdentity(
    val deviceId: String,
    val displayName: String,
    val deviceType: EmbodimentType,
    val capabilities: Set<String>,
    val autoConnect: Boolean,
    val lastSeen: Instant? = null
)
