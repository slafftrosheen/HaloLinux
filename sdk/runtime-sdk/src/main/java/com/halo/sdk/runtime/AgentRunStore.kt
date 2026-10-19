package com.halo.sdk.runtime

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Unique identifier for an agent run.
 */
@JvmInline
value class AgentRunId(val value: String)

/**
 * Unique identifier for an agent step.
 */
@JvmInline
value class AgentStepId(val value: String)

/**
 * Unique identifier for an agent input request.
 */
@JvmInline
value class AgentInputRequestId(val value: String)

/**
 * Resolution for an agent input request.
 */
data class AgentInputResolution(
    val requestId: AgentInputRequestId,
    val response: String,
    val resolvedAt: Instant = Instant.now(),
)

/**
 * Agent run state machine.
 * 
 * Terminal states: COMPLETED, FAILED, CANCELLED, INTERRUPTED
 * Terminal states are immutable unless a new attempt/run is created.
 * 
 * Legal transitions:
 * - CREATED -> RUNNING, CANCELLED
 * - RUNNING -> WAITING_FOR_TOOL, WAITING_FOR_USER, COMPLETED, FAILED, CANCELLED, INTERRUPTED
 * - WAITING_FOR_TOOL -> RUNNING, FAILED, CANCELLED, INTERRUPTED
 * - WAITING_FOR_USER -> RUNNING, CANCELLED
 * - INTERRUPTED -> QUEUED, CANCELLED, FAILED
 * - COMPLETED, FAILED, CANCELLED -> (no transitions)
 */
enum class AgentRunStatus {
    CREATED,
    QUEUED,
    RUNNING,
    WAITING_FOR_TOOL,
    WAITING_FOR_USER,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED;

    fun canTransitionTo(next: AgentRunStatus): Boolean = when (this) {
        CREATED -> next in setOf(RUNNING, CANCELLED)
        QUEUED -> next in setOf(RUNNING, CANCELLED)
        RUNNING -> next in setOf(
            WAITING_FOR_TOOL, WAITING_FOR_USER,
            COMPLETED, FAILED, CANCELLED, INTERRUPTED
        )
        WAITING_FOR_TOOL -> next in setOf(
            RUNNING, FAILED, CANCELLED, INTERRUPTED
        )
        WAITING_FOR_USER -> next in setOf(RUNNING, CANCELLED)
        INTERRUPTED -> next in setOf(QUEUED, CANCELLED, FAILED)
        COMPLETED, FAILED, CANCELLED -> false
    }

    val isTerminal: Boolean
        get() = this in setOf(COMPLETED, FAILED, CANCELLED, INTERRUPTED)
}

/**
 * Agent run event for append-only event journal.
 */
data class AgentRunEvent(
    val eventId: String,
    val runId: AgentRunId,
    val sequence: Long,
    val eventType: String,
    val schemaVersion: Int = 1,
    val payloadRef: String? = null,
    val correlationId: String? = null,
    val createdAt: Instant = Instant.now(),
)

/**
 * Agent step types for detailed execution journal.
 */
sealed interface AgentStep {
    val stepId: AgentStepId
    val runId: AgentRunId
    val sequence: Long
    val createdAt: Instant

    data class ToolDispatched(
        override val stepId: AgentStepId,
        override val runId: AgentRunId,
        override val sequence: Long,
        override val createdAt: Instant,
        val toolCallId: String,
        val toolName: String,
        val arguments: Map<String, Any>,
    ) : AgentStep

    data class ToolCompleted(
        override val stepId: AgentStepId,
        override val runId: AgentRunId,
        override val sequence: Long,
        override val createdAt: Instant,
        val toolCallId: String,
        val result: String,
        val actionTruth: ActionTruth,
    ) : AgentStep

    data class ToolFailed(
        override val stepId: AgentStepId,
        override val runId: AgentRunId,
        override val sequence: Long,
        override val createdAt: Instant,
        val toolCallId: String,
        val error: String,
    ) : AgentStep

    data class ToolRejected(
        override val stepId: AgentStepId,
        override val runId: AgentRunId,
        override val sequence: Long,
        override val createdAt: Instant,
        val toolCallId: String,
        val reason: String,
    ) : AgentStep

    data class ToolExecutionClaimed(
        override val stepId: AgentStepId,
        override val runId: AgentRunId,
        override val sequence: Long,
        override val createdAt: Instant,
        val toolCallId: String,
        val claimant: String,
    ) : AgentStep

    data class RecoveryClaimed(
        override val stepId: AgentStepId,
        override val runId: AgentRunId,
        override val sequence: Long,
        override val createdAt: Instant,
        val originalRunId: AgentRunId,
        val recoveredFromSequence: Long,
    ) : AgentStep

    data class InputResolved(
        override val stepId: AgentStepId,
        override val runId: AgentRunId,
        override val sequence: Long,
        override val createdAt: Instant,
        val requestId: AgentInputRequestId,
        val resolution: AgentInputResolution,
    ) : AgentStep

    data class StateChanged(
        override val stepId: AgentStepId,
        override val runId: AgentRunId,
        override val sequence: Long,
        override val createdAt: Instant,
        val fromStatus: AgentRunStatus,
        val toStatus: AgentRunStatus,
        val reason: String? = null,
    ) : AgentStep
}

/**
 * Action truth for tool execution verification.
 */
enum class ActionTruth {
    PROPOSED,
    AUTHORIZED,
    DISPATCHED,
    ACKNOWLEDGED,
    CONFIRMED,
    UNKNOWN,
    FAILED,
}

/**
 * Canonical AgentRunStore contract.
 * 
 * The host owns durable storage and atomic append/deduplication.
 * All state transitions are transactional with optimistic locking (revision).
 * The event journal provides monotonic sequence numbers per run.
 */
interface AgentRunStore {
    /**
     * Observe all runs as a flow for reactive UI.
     */
    fun observeRuns(): Flow<List<AgentRun>>

    /**
     * Get a single run by ID.
     */
    suspend fun get(runId: AgentRunId): AgentRun?

    /**
     * Create a new run.
     */
    suspend fun create(run: AgentRun)

    /**
     * Append a step to the run's event journal.
     * Returns false if this step identity already exists (deduplication).
     * Never dispatch a tool if this returns false.
     */
    suspend fun appendStep(step: AgentStep): Boolean

    /**
     * Update run state with optimistic locking.
     * Throws ConcurrentModificationException if revision mismatch.
     */
    suspend fun update(run: AgentRun)

    /**
     * Get child runs for a parent run.
     */
    suspend fun children(parent: AgentRunId): List<AgentRun>

    /**
     * Create a child run if allowed (depth/identity/count checks).
     * Returns false if not allowed.
     */
    suspend fun createChildIfAllowed(parent: AgentRunId, child: AgentRun, maxChildren: Int): Boolean

    /**
     * Every run that has not reached a terminal state.
     */
    suspend fun nonTerminalRuns(): List<AgentRun>

    /**
     * Atomic resume claim (compare-and-swap on status).
     * Exactly one caller wins when several surfaces try to resume the same run.
     * Returns false when the run is not in [expectedState] (or is terminal).
     */
    suspend fun claimResume(runId: AgentRunId, expectedState: AgentRunStatus): Boolean

    /**
     * Atomic recovery claim + durable recovery fingerprint.
     * Appends a RecoveryClaimed step when the CAS succeeds.
     */
    suspend fun claimRecovery(
        runId: AgentRunId,
        expectedState: AgentRunStatus,
        claim: AgentStep.RecoveryClaimed,
    ): Boolean

    /**
     * Atomic input resolution (first valid resolution wins).
     * Appends exactly one InputResolved step when the request is journaled
     * on a non-terminal run and still pending. Returns false otherwise
     * (unknown request, already resolved, terminal run).
     */
    suspend fun resolveInputIfPending(
        requestId: AgentInputRequestId,
        resolution: AgentInputResolution,
    ): Boolean
}

/**
 * Agent run aggregate.
 */
data class AgentRun(
    val id: AgentRunId,
    val interactionId: String,
    val conversationId: String?,
    val originNodeId: String?,
    val status: AgentRunStatus,
    val attempt: Int = 0,
    val revision: Long = 0,
    val checkpointRef: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val failureCategory: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val lastDurableCheckpoint: Long? = null,
) {
    companion object {
        fun createNew(
            interactionId: String,
            conversationId: String? = null,
            originNodeId: String? = null,
            providerId: String? = null,
            modelId: String? = null,
        ): AgentRun {
            return AgentRun(
                id = AgentRunId(UUID.randomUUID().toString()),
                interactionId = interactionId,
                conversationId = conversationId,
                originNodeId = originNodeId,
                status = AgentRunStatus.CREATED,
                providerId = providerId,
                modelId = modelId,
            )
        }
    }
}