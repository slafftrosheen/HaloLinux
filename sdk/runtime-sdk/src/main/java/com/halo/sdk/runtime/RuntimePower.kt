package com.halo.sdk.runtime

/** Runtime power policy selected by the host from current device conditions. */
enum class RuntimePowerMode {
    PERFORMANCE,
    BALANCED,
    LOW_POWER,
    CHARGING
}

data class RuntimePowerPolicy(
    val mode: RuntimePowerMode,
    val audioFrameIntervalMillis: Long,
    val allowBackgroundInference: Boolean,
    val retainLoadedModels: Boolean
) {
    companion object {
        fun forMode(mode: RuntimePowerMode): RuntimePowerPolicy = when (mode) {
            RuntimePowerMode.PERFORMANCE -> RuntimePowerPolicy(mode, 10L, true, true)
            RuntimePowerMode.BALANCED -> RuntimePowerPolicy(mode, 20L, true, true)
            RuntimePowerMode.LOW_POWER -> RuntimePowerPolicy(mode, 50L, false, false)
            RuntimePowerMode.CHARGING -> RuntimePowerPolicy(mode, 10L, true, true)
        }
    }
}