package com.halo.host.linux

import com.halo.linux.agent.CanonicalRecoveryInspector
import com.halo.linux.agent.SqliteAgentRunStore
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

    override suspend fun inspect(): JSONObject {
        val plans = inspector.inspect()
        val counts = JSONObject()
        plans.groupingBy { it.planKind }.eachCount()
            .toSortedMap().forEach { (kind, count) -> counts.put(kind, count) }
        return JSONObject()
            .put("state", "CLASSIFICATION_ONLY")
            .put("candidate_count", plans.size)
            .put("plan_classes", counts)
            .put("automatic_replay", false)
            .put("tool_execution", false)
    }

    override fun close() = store.close()
}
