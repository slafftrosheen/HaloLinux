package com.halo.sdk.runtime

import java.time.Duration
import java.time.Instant
import com.halo.sdk.host.DeviceContext
import com.halo.sdk.runtime.resource.ResourceLeaseSnapshot

data class RuntimeHealth(
    val healthy: Boolean,
    val uptime: Duration,
    val degraded: Boolean,
    val lastFailure: Instant?
)

data class RuntimeSnapshot(
    val runtimeVersion: String,
    val buildNumber: Int,
    val startedAt: Instant,
    val health: RuntimeHealth,
    val runtimeState: RuntimeState,
    val speech: SpeechSessionState,
    val engines: List<EngineSnapshot>,
    val timeline: List<TimelineEvent>,
    val providers: List<ProviderSnapshot>,
    val blackboard: BlackboardSnapshot,
    val deviceContext: DeviceContext = DeviceContext(),
    val traces: List<ObservationTrace> = emptyList(),
    /** Native heavyweight residency, eviction and contention diagnostics. */
    val nativeResources: List<NativeResourceSnapshot> = emptyList(),
    /** Generic runtime resource leases and wait queue diagnostics. */
    val resourceLeases: ResourceLeaseSnapshot? = null
)

data class StageMetrics(
    val stage: RuntimeStage,
    val startedAt: Long,
    val finishedAt: Long,
    val latency: Long,
    val success: Boolean,
    val error: String? = null
)

enum class TraceState {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

enum class TraceValidation {
    VALID,
    INVALID,
    PENDING
}

data class ObservationTrace(
    val observationId: String,
    val startedAt: Long,
    val finishedAt: Long,
    val totalLatency: Long,
    val state: TraceState,
    val validation: TraceValidation,
    val stages: List<StageMetrics>
)

data class TimelineEvent(
    val presentationId: String,
    val correlationId: String?,
    val title: String,
    val description: String,
    val timestamp: Instant,
    val status: String,
    val intentId: String? = null,
    val category: String = "All",
    val objectId: String? = null,
    val details: Map<String, String> = emptyMap()
)

data class ProviderSnapshot(
    val id: String,
    val name: String,
    val status: String,
    val statusMessage: String? = null,
    val isSelected: Boolean = false,
    val activeModel: String? = null
)

data class FactSummary(
    val id: String,
    val type: String,
    val createdAt: Instant
)

data class BlackboardSnapshot(
    val factCount: Int,
    val recentFacts: List<FactSummary>
)

data class EngineMetrics(
    val latencyMs: Long?,
    val uptime: Duration,
    val eventsProcessed: Long,
    val queueSize: Int,
    val cpuUsage: Double
)

data class EngineSnapshot(
    val id: String,
    val state: EngineState,
    val health: EngineHealth,
    val metrics: EngineMetrics,
    val lastActivity: Instant
)
