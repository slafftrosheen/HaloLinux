package com.halo.linux.agent

import com.halo.runtime.cognition.agent.AgentRunState
import com.halo.runtime.cognition.agent.AgentRunStore
import com.halo.runtime.cognition.agent.recovery.CapabilitySnapshot
import com.halo.runtime.cognition.agent.recovery.DefaultAgentRecoveryPlanner
import com.halo.runtime.cognition.agent.recovery.RecoveryPlan

/** No prompts, tool arguments, private context, or model results in the report. */
data class CanonicalRecoveryCandidate(
    val runId: String,
    val persistedState: AgentRunState,
    val planKind: String,
    val requiresUserInput: Boolean,
    val automaticReplayPermitted: Boolean = false,
)

/**
 * Pure startup classification using HALO's real recovery planner.
 * A planner recommending RetryProviderTurn or RetryTool is NOT authority to
 * execute it on Linux. All candidates require explicit future admission.
 */
class CanonicalRecoveryInspector(private val store: AgentRunStore) {
    private val planner = DefaultAgentRecoveryPlanner()

    suspend fun inspect(): List<CanonicalRecoveryCandidate> =
        store.nonTerminalRuns().map { run ->
            val plan = planner.plan(run, catalog = null, capabilities = CapabilitySnapshot())
            CanonicalRecoveryCandidate(
                runId = run.id.value,
                persistedState = run.state,
                planKind = plan.javaClass.simpleName,
                requiresUserInput = when (plan) {
                    is RecoveryPlan.AwaitingInput,
                    is RecoveryPlan.UncertainExternalAction,
                    is RecoveryPlan.MissingContext -> true
                    else -> false
                }
            )
        }
}
