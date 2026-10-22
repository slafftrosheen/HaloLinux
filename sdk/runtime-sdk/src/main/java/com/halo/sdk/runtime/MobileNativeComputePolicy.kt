package com.halo.sdk.runtime

/**
 * Limits *concurrent active heavy compute*, not background residency or platform routing.
 *
 * Android's reported totalMem is lower than the marketed RAM (8 GB phones typically report
 * less than 8 GiB). On such devices independent ASR + LiteRT GPU/TTS executions can each
 * allocate large native heaps; free RAM at admission alone does not guarantee peak safety.
 * Serializing the heavyweight execution leases avoids simultaneous peak allocations while
 * preserving the resident engine's fast follow-up turns.
 *
 * This is a conservative, deterministic default. Benchmarks can later justify a device-specific
 * override; it must not be inferred from a GPU metadata capability flag.
 */
object MobileNativeComputePolicy {
    private const val GIB = 1024L * 1024L * 1024L
    const val SMALL_PHONE_RAM_LIMIT_BYTES = 9L * GIB
    const val PARALLEL_EXECUTION_HEADROOM_BYTES = 3L * GIB

    fun isConstrainedPhone(totalBytes: Long): Boolean =
        totalBytes > 0 && totalBytes <= SMALL_PHONE_RAM_LIMIT_BYTES

    /** Android totalMem, not the advertised retail RAM. */
    enum class DeviceTier(val label: String) {
        FOUR_GB("4 GB class"), SIX_GB("6 GB class"), EIGHT_GB("8 GB class"),
        UNCONSTRAINED("Larger device"), UNKNOWN("Unknown RAM")
    }

    fun tier(totalBytes: Long): DeviceTier = when {
        totalBytes <= 0 -> DeviceTier.UNKNOWN
        totalBytes <= 4_500_000_000L -> DeviceTier.FOUR_GB
        totalBytes <= 6_500_000_000L -> DeviceTier.SIX_GB
        totalBytes <= SMALL_PHONE_RAM_LIMIT_BYTES -> DeviceTier.EIGHT_GB
        else -> DeviceTier.UNCONSTRAINED
    }

    /** Time an idle model can be kept warm after execution: CPU speech may remain independent. */
    fun idleGraceMillis(totalBytes: Long): Long = when (tier(totalBytes)) {
        DeviceTier.FOUR_GB, DeviceTier.UNKNOWN -> 3_000L
        DeviceTier.SIX_GB -> 10_000L
        DeviceTier.EIGHT_GB, DeviceTier.UNCONSTRAINED -> 30_000L
    }

    /** On 4/6 GB AUTO is CPU-first; explicit developer GPU remains possible. */
    fun autoGpuAllowed(totalBytes: Long): Boolean = when (tier(totalBytes)) {
        DeviceTier.FOUR_GB, DeviceTier.SIX_GB, DeviceTier.UNKNOWN -> false
        else -> true
    }

    fun allowConcurrentHeavyweights(
        totalBytes: Long,
        availableBytes: Long,
        pressure: NativeMemoryPressure,
    ): Boolean = pressure == NativeMemoryPressure.NORMAL &&
        totalBytes > SMALL_PHONE_RAM_LIMIT_BYTES &&
        availableBytes > PARALLEL_EXECUTION_HEADROOM_BYTES
}
