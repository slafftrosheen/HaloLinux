package com.halo.sdk.runtime.embodiment.heycyan

/**
 * High-level execution result for [HeyCyanController.execute].
 */
sealed interface HeyCyanExecutionResult {
    data object Acknowledged : HeyCyanExecutionResult
    data class Completed(val metadata: Map<String, String> = emptyMap()) : HeyCyanExecutionResult
}

/**
 * Controller interface for HeyCyan devices.
 * Implemented by the driver and used by [HeyCyanSession].
 */
interface HeyCyanController {
    suspend fun execute(operation: HeyCyanOperation): Result<HeyCyanExecutionResult>
}
