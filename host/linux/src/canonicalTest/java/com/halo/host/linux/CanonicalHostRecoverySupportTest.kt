package com.halo.host.linux

import com.halo.cognition.language.InteractionId
import com.halo.linux.agent.SqliteAgentRunStore
import com.halo.runtime.cognition.agent.AgentRun
import com.halo.runtime.cognition.agent.AgentRunId
import com.halo.runtime.cognition.agent.AgentRunState
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** This class is only compiled when -PhaloCanonicalCognition=true. */
class CanonicalHostRecoverySupportTest {
    @TempDir lateinit var temp: Path

    @Test fun daemonReportsCanonicalRecoveryWithoutExecutingAnything() = runBlocking {
        CanonicalHostRecoverySupport(temp).use { adapter ->
            val now = Instant.parse("2026-10-12T00:00:00Z")
            SqliteAgentRunStore(temp.resolve("canonical-agent-runs.db")).use { store ->
                store.create(AgentRun(
                    id = AgentRunId("unfinished-run"),
                    interactionId = InteractionId("test-interaction"),
                    request = "Private content must not show up in recovery status",
                    state = AgentRunState.SUSPENDED,
                    createdAt = now,
                    updatedAt = now,
                ))
            }
            val status = adapter.inspect()
            assertEquals("CLASSIFICATION_ONLY", status.getString("state"))
            assertEquals(1, status.getInt("candidate_count"))
            assertEquals(1, status.getJSONObject("plan_classes")
                .getInt("UnsupportedLegacyState"))
            assertFalse(status.getBoolean("automatic_replay"))
            assertFalse(status.getBoolean("tool_execution"))
            assertFalse(status.toString().contains("Private content"))
        }
    }
}
