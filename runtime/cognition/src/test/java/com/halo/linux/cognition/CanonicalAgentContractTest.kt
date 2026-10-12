package com.halo.linux.cognition

import com.halo.cognition.language.InteractionId
import com.halo.runtime.cognition.agent.AgentRun
import com.halo.runtime.cognition.agent.AgentRunId
import com.halo.runtime.cognition.agent.AgentRunState
import com.halo.runtime.cognition.agent.isTerminal
import com.halo.runtime.cognition.agent.recovery.CapabilitySnapshot
import com.halo.runtime.cognition.agent.recovery.DefaultAgentRecoveryPlanner
import com.halo.runtime.cognition.agent.recovery.RecoveryPlan
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Compiles/runs ONLY with source from the SHA-pinned canonical Halo project.
 * The test deliberately exercises the REAL Halo recovery planner, not a fork.
 */
class CanonicalAgentContractTest {
    @Test fun terminalStateContract() {
        assertTrue(AgentRunState.COMPLETED.isTerminal)
        assertTrue(AgentRunState.FAILED.isTerminal)
        assertTrue(AgentRunState.INTERRUPTED.isTerminal)
        assertFalse(AgentRunState.SUSPENDED.isTerminal)
    }

    @Test fun unjournaledRunDoesNotBecomeAutonomousReplay() {
        val now = Instant.now()
        val run = AgentRun(
            id = AgentRunId("linux-contract-run"),
            interactionId = InteractionId("linux-contract-interaction"),
            request = "Private test prompt",
            state = AgentRunState.SUSPENDED,
            createdAt = now,
            updatedAt = now,
        )
        val plan = DefaultAgentRecoveryPlanner().plan(
            run = run, catalog = null, capabilities = CapabilitySnapshot()
        )
        assertTrue(plan is RecoveryPlan.UnsupportedLegacyState,
            "Missing durable journal must never authorize replay")
    }
}
