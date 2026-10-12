package com.halo.sdk.runtime
import java.time.Duration

data class RuntimeMetrics(
    val eventsPublished: Long,
    val commandsExecuted: Long,
    val failedCommands: Long,
    val averageLatency: Duration,
    val peakMemory: Long,
    val activeTasks: Int
)
