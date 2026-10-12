package com.halo.host.linux

import com.halo.linux.agent.CanonicalRecoveryInspector
import com.halo.linux.agent.SqliteAgentRunStore
import com.halo.runtime.cognition.agent.DefaultAgentRunCoordinator
import com.halo.runtime.cognition.agent.AgentRequest
import com.halo.runtime.cognition.agent.AgentRunId
import com.halo.runtime.cognition.agent.AgentRunState
import com.halo.runtime.cognition.agent.AgentStep
import com.halo.runtime.cognition.agent.AgentStepId
import com.halo.runtime.cognition.agent.ProviderTurnId
import com.halo.cognition.language.InteractionId
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.halo.runtime.cognition.agent.RecoveryClassification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Path
import org.json.JSONObject

/**
 * Compiled ONLY with -PhaloCanonicalCognition=true and a SHA-pinned Halo tree.
 * Classification is read-only. No call to claimResume, claimRecovery, tool
 * execution or provider dispatch occurs during daemon startup/status.
 */
class CanonicalHostRecoverySupport(dataDir: Path) : LinuxRecoverySupport {
    private val store = SqliteAgentRunStore(dataDir.resolve("canonical-agent-runs.db"))
    private val inspector = CanonicalRecoveryInspector(store)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val coordinator = DefaultAgentRunCoordinator(store, scope)
    private var startupSummary: Map<String, Int> = emptyMap()
    private var prepared = false

    override suspend fun prepare() {
        check(!prepared) { "Canonical startup classification must run once" }
        val classified = coordinator.prepareOutstandingRunsForRecovery()
        startupSummary = classified.values.groupingBy {
            when (it) {
                is RecoveryClassification.Recoverable -> "Recoverable"
                is RecoveryClassification.WaitingInput -> "WaitingInput"
                is RecoveryClassification.Unrecoverable -> "Unrecoverable"
            }
        }.eachCount()
        prepared = true
    }

    override suspend fun inspect(): JSONObject {
        check(prepared) { "Canonical recovery has not been classified at startup" }
        val plans = inspector.inspect()
        val counts = JSONObject()
        plans.groupingBy { it.planKind }.eachCount()
            .toSortedMap().forEach { (kind, count) -> counts.put(kind, count) }
        return JSONObject()
            .put("state", "CLASSIFICATION_ONLY")
            .put("candidate_count", plans.size)
            .put("plan_classes", counts)
            .put("startup_classifications", JSONObject(startupSummary))
            .put("coordinator", "canonical")
            .put("startup_prepared", prepared)
            .put("automatic_replay", false)
            .put("tool_execution", false)
    }

    private val textTurns = ConcurrentHashMap<String, String>()

    override suspend fun beginTextTurn(
        interactionId: String, text: String, providerId: String?, modelId: String?,
    ): String {
        check(prepared) { "Canonical coordinator is not prepared" }
        require(text.isNotBlank() && text.length <= 32_000)
        val runId = coordinator.start(AgentRequest(InteractionId(interactionId), text))
        coordinator.transition(runId, AgentRunState.EXECUTING)
        // A stable provider turn identity is persisted BEFORE inference starts.
        val turnId = ProviderTurnId(runId.value + ":provider:1")
        val start = AgentStep.ProviderTurnStarted(
            id = AgentStepId(UUID.randomUUID().toString()),
            runId = runId, createdAt = Instant.now(),
            turnId = turnId,
            providerId = providerId ?: "unconfigured",
            modelId = modelId,
            requestIdentity = interactionId,
            exposedToolIds = emptySet(),
            requiredCapabilities = emptySet(),
            toolUseMode = "NONE",
            workloadKind = "TEXT",
        )
        check(coordinator.recordStep(start)) { "Provider turn already recorded" }
        textTurns[runId.value] = turnId.value
        return runId.value
    }

    override suspend fun recordTextResponse(runId: String?, response: String) {
        if (runId == null) return
        val turnId = textTurns[runId] ?: error("Unknown active canonical text turn")
        val id = AgentRunId(runId)
        val now = Instant.now()
        check(coordinator.recordStep(AgentStep.ProviderTurnCompleted(
            id = AgentStepId(UUID.randomUUID().toString()),
            runId = id, createdAt = now,
            turnId = ProviderTurnId(turnId),
            assistantText = response, toolCalls = emptyList(),
            finishReason = "stop",
        ))) { "Provider turn completion already recorded" }
        check(coordinator.recordStep(AgentStep.ResponseProduced(
            id = AgentStepId(UUID.randomUUID().toString()),
            runId = id, createdAt = now,
            responseId = runId + ":response:1",
            text = response,
        ))) { "Response evidence already recorded" }
    }

    override suspend fun commitTextDelivery(runId: String?, conversationId: String) {
        if (runId == null) return
        val id = AgentRunId(runId)
        // The main SQLite conversation was already committed before this call.
        check(coordinator.recordStep(AgentStep.ResponseCommitted(
            id = AgentStepId(UUID.randomUUID().toString()),
            runId = id, createdAt = Instant.now(),
            commitId = runId + ":delivery:1",
            interactionId = conversationId,
        ))) { "Response delivery already recorded" }
        coordinator.complete(id)
        textTurns.remove(runId)
    }

    override suspend fun failTextTurn(runId: String?, reasonCode: String) {
        if (runId == null) return
        require(reasonCode.matches(Regex("[A-Z_]{1,64}")))
        textTurns.remove(runId)
        // Do not create new claims or replay the model on a failed turn.
        coordinator.fail(AgentRunId(runId), reasonCode)
    }

    override suspend fun backup(destination: Path): JSONObject {
        val bytes = store.backupTo(destination)
        return JSONObject().put("ok", true)
            .put("backup_path", destination.toString())
            .put("bytes", bytes)
            .put("contains_private_agent_runs", true)
    }

    override fun close() {
        scope.cancel()
        store.close()
    }
}
