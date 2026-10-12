package com.halo.host.linux

import com.halo.linux.agent.CanonicalRecoveryInspector
import com.halo.linux.agent.SqliteAgentRunStore
import com.halo.runtime.cognition.agent.DefaultAgentRunCoordinator
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

    override fun close() {
        scope.cancel()
        store.close()
    }
}
