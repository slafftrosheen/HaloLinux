package com.halo.sdk.runtime.resource

@JvmInline
value class ResourceId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class ResourceEndpointId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class ResourceLeaseId(val value: String) {
    override fun toString(): String = value
}

data class ResourceKey(
    val resourceId: ResourceId,
    val endpointId: ResourceEndpointId,
)

/**
 * Standard Halo semantic resource identities.
 */
object HaloResourceIds {
    val AudioInput = ResourceId("audio.input")
    val AudioOutput = ResourceId("audio.output")
    val AudioSession = ResourceId("audio.session")
    val CameraSensor = ResourceId("camera.sensor")
    val CameraCapture = ResourceId("camera.capture")
    val VideoCapture = ResourceId("video.capture")
    val DisplayOutput = ResourceId("display.output")
    val MediaTransfer = ResourceId("media.transfer")
    val ScreenCapture = ResourceId("screen.capture")
    val ComputeInference = ResourceId("compute.inference")
    val ComputeAsr = ResourceId("compute.asr")
    val ComputeTts = ResourceId("compute.tts")
    val ComputeHeavy = ResourceId("compute.heavy")
    val NetworkHighBandwidth = ResourceId("network.high_bandwidth")
    val MobilityDrive = ResourceId("mobility.drive")
    val ManipulatorMotion = ResourceId("manipulator.motion")
}

enum class ResourceHolderKind {
    INTERACTION,
    TOOL,
    PROVIDER,
    CONTEXT_ACQUISITION,
    SYSTEM,
}

data class ResourceHolder(
    val id: String,
    val interactionId: String? = null,
    val runId: String? = null,
    val toolInvocationId: String? = null,
    val kind: ResourceHolderKind = ResourceHolderKind.SYSTEM,
)

enum class ResourceAccess {
    SHARED,
    EXCLUSIVE,
}

data class ResourceClaim(
    val key: ResourceKey,
    val access: ResourceAccess = ResourceAccess.EXCLUSIVE,
)

enum class ResourcePriority {
    MAINTENANCE,
    BACKGROUND,
    USER_VISIBLE_BACKGROUND,
    INTERACTIVE,
    INTERACTIVE_INPUT,
}

sealed interface ResourceWaitPolicy {
    data object FailFast : ResourceWaitPolicy
    data class Wait(val timeoutMs: Long) : ResourceWaitPolicy
    data object SuspendRun : ResourceWaitPolicy
}

enum class ResourcePreemptionPolicy {
    NEVER,
    COOPERATIVE,
}

enum class ResourceYieldDecision {
    RELEASING,
    REFUSED,
}

data class ResourceYieldRequest(
    val requestedClaim: ResourceClaim,
    val requestingHolder: ResourceHolder,
    val requestingPriority: ResourcePriority,
    val heldLeaseId: ResourceLeaseId,
    val heldClaim: ResourceClaim,
)

fun interface ResourceYieldHandler {
    suspend fun requestYield(request: ResourceYieldRequest): ResourceYieldDecision
}

data class ResourceLeaseRequest(
    val requestId: String,
    val holder: ResourceHolder,
    val claims: Set<ResourceClaim>,
    val priority: ResourcePriority = ResourcePriority.INTERACTIVE,
    val waitPolicy: ResourceWaitPolicy = ResourceWaitPolicy.FailFast,
    val preemption: ResourcePreemptionPolicy = ResourcePreemptionPolicy.COOPERATIVE,
)

data class ResourceConflict(
    val requested: ResourceClaim,
    val heldBy: ResourceHolder,
    val heldLeaseId: ResourceLeaseId,
    val heldSinceMs: Long,
    val priority: ResourcePriority,
)

sealed interface ResourceAcquireResult {
    data class Granted(val lease: ResourceLease) : ResourceAcquireResult
    data class Busy(val conflicts: List<ResourceConflict>) : ResourceAcquireResult
    data class TimedOut(val conflicts: List<ResourceConflict>) : ResourceAcquireResult
    data class Unavailable(val reason: String) : ResourceAcquireResult
}

data class ResourceLeaseRecord(
    val leaseId: ResourceLeaseId,
    val holder: ResourceHolder,
    val claims: Set<ResourceClaim>,
    val priority: ResourcePriority,
    val acquiredAtMs: Long,
)

data class ResourceWaitRecord(
    val requestId: String,
    val holder: ResourceHolder,
    val claims: Set<ResourceClaim>,
    val priority: ResourcePriority,
    val requestedAtMs: Long,
)

data class ResourceLeaseSnapshot(
    val active: List<ResourceLeaseRecord>,
    val waiting: List<ResourceWaitRecord>,
)

sealed interface ResourceRuntimeEvent {
    val timestampMs: Long

    data class LeaseRequested(
        override val timestampMs: Long,
        val requestId: String,
        val holder: ResourceHolder,
        val claims: Set<ResourceClaim>,
    ) : ResourceRuntimeEvent

    data class LeaseGranted(
        override val timestampMs: Long,
        val leaseId: ResourceLeaseId,
        val holder: ResourceHolder,
        val claims: Set<ResourceClaim>,
    ) : ResourceRuntimeEvent

    data class LeaseBlocked(
        override val timestampMs: Long,
        val requestId: String,
        val holder: ResourceHolder,
        val conflicts: List<ResourceConflict>,
    ) : ResourceRuntimeEvent

    data class YieldRequested(
        override val timestampMs: Long,
        val targetLeaseId: ResourceLeaseId,
        val requestingHolder: ResourceHolder,
    ) : ResourceRuntimeEvent

    data class LeaseReleased(
        override val timestampMs: Long,
        val leaseId: ResourceLeaseId,
        val holder: ResourceHolder,
    ) : ResourceRuntimeEvent
}

sealed interface ResourceExecutionResult<out T> {
    data class Success<T>(val value: T, val lease: ResourceLease) : ResourceExecutionResult<T>
    data class FailedToAcquire(val result: ResourceAcquireResult) : ResourceExecutionResult<Nothing>
    data class Error(val throwable: Throwable) : ResourceExecutionResult<Nothing>
}
