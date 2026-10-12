package com.halo.sdk.runtime

/**
 * Snapshot of the current hardware and platform state.
 */
data class DeviceState(
    val properties: Map<String, Any> = emptyMap()
)
