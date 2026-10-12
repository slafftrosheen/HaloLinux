package com.halo.linux.agent

import com.halo.cognition.language.InteractionId
import com.halo.runtime.cognition.agent.*
import com.halo.runtime.cognition.tools.ToolId
import com.halo.runtime.cognition.agent.recovery.DefaultAgentRecoveryPlanner
import com.halo.runtime.cognition.agent.recovery.CapabilitySnapshot
import com.halo.runtime.cognition.agent.recovery.RecoveryPlan
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SqliteAgentRunStoreTest {
    @TempDir lateinit var folder: Path
    private val start = Instant.parse("2026-10-12T00:00:00Z")
    private fun db() = SqliteAgentRunStore(folder.resolve("halo-agent-runs.db").toAbsolutePath())
    private fun run(id: String, state: AgentRunState = AgentRunState.SUSPENDED) = AgentRun(
        id = AgentRunId(id), interactionId = InteractionId("i-$id"),
        request = "A private request: $id", state = state,
        createdAt = start, updatedAt = start,
    )
    private fun step(runId: String, suffix: String) = AgentStep.RunPrepared(
        id = AgentStepId("step-$runId-$suffix"), runId = AgentRunId(runId),
        createdAt = start, kind = AgentRunKind.INTERACTIVE,
        recoveryPolicy = RunRecoveryPolicy.USER_RESUME,
    )

    @Test fun journalRoundTripsAcrossRestartAndEmitsObservedState() = runBlocking {
        db().use { storage ->
            storage.create(run("first"))
            assertTrue(storage.appendStep(step("first", "0")))
            assertFalse(storage.appendStep(step("first", "0")))
            assertEquals(1, storage.observeRuns().first().single().steps.size)
            assertEquals(1, storage.get(AgentRunId("first"))!!.steps.size)
            assertThrows(IllegalStateException::class.java) {
                runBlocking { storage.create(run("first")) }
            }
        }
        db().use { reopened ->
            val saved = reopened.get(AgentRunId("first"))!!
            assertEquals("A private request: first", saved.request)
            assertEquals(AgentRunState.SUSPENDED, saved.state)
            assertTrue(saved.steps.single() is AgentStep.RunPrepared)
            assertEquals(1, reopened.nonTerminalRuns().size)
        }
    }

    @Test fun resumeAndRecoveryClaimsAreCASAndPersistent() = runBlocking {
        db().use { storage ->
            storage.create(run("resume"))
            assertTrue(storage.claimResume(AgentRunId("resume"), AgentRunState.SUSPENDED))
            assertFalse(storage.claimResume(AgentRunId("resume"), AgentRunState.SUSPENDED))
            assertFalse(storage.claimResume(AgentRunId("resume"), AgentRunState.EXECUTING))
            storage.create(run("claim"))
            val recovery = AgentStep.RecoveryClaimed(
                id = AgentStepId("recovery-1"), runId = AgentRunId("claim"),
                createdAt = start, planKind = "RetryProviderTurn", evidenceStepId = "evidence"
            )
            assertTrue(storage.claimRecovery(AgentRunId("claim"), AgentRunState.SUSPENDED, recovery))
            assertFalse(storage.claimRecovery(AgentRunId("claim"), AgentRunState.SUSPENDED, recovery))
            assertEquals(1, storage.get(AgentRunId("claim"))!!.steps.size)
        }
        db().use { storage ->
            assertEquals(AgentRunState.EXECUTING, storage.get(AgentRunId("claim"))?.state)
            assertTrue(storage.get(AgentRunId("claim"))?.steps?.single() is AgentStep.RecoveryClaimed)
        }
    }

    @Test fun onlyOneInputResolutionCanWinAndSurvivesRestart() = runBlocking {
        db().use { storage ->
            storage.create(run("approval", AgentRunState.WAITING_FOR_APPROVAL))
            val inputId = AgentInputRequestId("approval-request")
            val pending = AgentStep.InputRequested(
                id = AgentStepId("requested"), runId = AgentRunId("approval"),
                createdAt = start, requestId = inputId,
                input = AgentInputRequest.Approval(
                    id = inputId, runId = AgentRunId("approval"), createdAt = start,
                    invocationId = ToolInvocationId("invocation-1"),
                    toolId = ToolId("example-tool"), action = "Confirm action",
                    risk = "medium", argumentsDigest = "sha256"
                )
            )
            assertTrue(storage.appendStep(pending))
            assertFalse(storage.appendStep(pending))
            assertTrue(storage.resolveInputIfPending(inputId, AgentInputResolution.Denied))
            assertFalse(storage.resolveInputIfPending(inputId, AgentInputResolution.Approved))
            assertFalse(storage.resolveInputIfPending(
                AgentInputRequestId("missing"), AgentInputResolution.Approved))
        }
        db().use { storage ->
            val steps = storage.get(AgentRunId("approval"))!!.steps
            assertEquals(2, steps.size)
            assertEquals("Denied", (steps.last() as AgentStep.InputResolved).resolution)
        }
    }

    @Test fun childCountAndAncestryAreCheckedAtomically() = runBlocking {
        db().use { store ->
            store.create(run("parent", AgentRunState.EXECUTING))
            fun child(name: String) = run(name, AgentRunState.CREATED).copy(
                parentRunId = AgentRunId("parent"),
                rootRunId = AgentRunId("parent"),
                delegationDepth = 1,
            )
            assertTrue(store.createChildIfAllowed(AgentRunId("parent"), child("one"), 1))
            assertFalse(store.createChildIfAllowed(AgentRunId("parent"), child("two"), 1))
            assertEquals(1, store.children(AgentRunId("parent")).size)
            assertFalse(store.createChildIfAllowed(AgentRunId("parent"), child("wrong").copy(
                delegationDepth = 17
            ), 10))
        }
    }

    @Test fun unimplementedToolDispatchCannotEnterJournal() = runBlocking {
        db().use { store ->
            store.create(run("safe"))
            assertThrows(UnsupportedOperationException::class.java) {
                runBlocking {
                    store.appendStep(AgentStep.ToolDispatched(
                        id = AgentStepId("dispatched"), runId = AgentRunId("safe"),
                        createdAt = start, toolCallId = "call", toolId = "shell", idempotencyKey = "x"
                    ))
                }
            }
            assertTrue(store.get(AgentRunId("safe"))!!.steps.isEmpty())
        }
    }

    @Test fun canonicalRecoveryPlannerIsInspectionOnly() = runBlocking {
        db().use { store ->
            store.create(run("unfinished"))
            store.appendStep(step("unfinished", "prepared"))
            store.appendStep(AgentStep.ProviderTurnStarted(
                id = AgentStepId("provider-start"), runId = AgentRunId("unfinished"),
                createdAt = start, turnId = ProviderTurnId("turn-1"), providerId = "fixture",
                modelId = "fixture-model", requestIdentity = "request-digest",
                exposedToolIds = emptySet(), requiredCapabilities = emptySet()
            ))
            val saved = store.get(AgentRunId("unfinished"))!!
            assertTrue(DefaultAgentRecoveryPlanner().plan(
                saved, null, CapabilitySnapshot()
            ) is RecoveryPlan.RetryProviderTurn)
            val report = CanonicalRecoveryInspector(store).inspect().single()
            assertEquals("RetryProviderTurn", report.planKind)
            assertFalse(report.automaticReplayPermitted)
        }
        db().use { reopened ->
            assertEquals("RetryProviderTurn",
                CanonicalRecoveryInspector(reopened).inspect().single().planKind)
        }
    }

    @Test fun schemaAndStepTypesFailClosed() {
        val unknownVersion = """{"v":999,"type":"ResponseCommitted","id":"x","runId":"r","createdAt":"2026-01-01T00:00:00Z","commitId":"c","interactionId":"i"}"""
        assertThrows(IllegalArgumentException::class.java) {
            CanonicalAgentJson.decodeStep(unknownVersion)
        }
        val unknownStep = """{"v":1,"type":"NewUnreviewedStep","id":"x","runId":"r","createdAt":"2026-01-01T00:00:00Z"}"""
        assertThrows(UnsupportedOperationException::class.java) {
            CanonicalAgentJson.decodeStep(unknownStep)
        }
    }
}
