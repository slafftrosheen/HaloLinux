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

    @Test fun claimedExecutionIsNotAutomaticallyConfirmedOrRepeated() = runBlocking {
        db().use { store ->
            store.create(run("safe"))
            val claim = AgentStep.ToolExecutionClaimed(
                id = AgentStepId("claimed-1"), runId = AgentRunId("safe"),
                createdAt = start, invocationId = ToolInvocationId("invocation-1"),
                toolCallId = "call-1", toolId = "dangerous-action",
                idempotencyKey = "once-ever-1", replaySafety = "NEVER_REPLAY"
            )
            assertTrue(store.appendStep(claim))
            assertFalse(store.appendStep(claim))
            val repeated = claim.copy(id=AgentStepId("claimed-2"))
            assertFalse(store.appendStep(repeated))
            val record = store.get(AgentRunId("safe"))!!.steps.single()
            assertTrue(record is AgentStep.ToolExecutionClaimed)
            assertEquals("NEVER_REPLAY", (record as AgentStep.ToolExecutionClaimed).replaySafety)
            // A claim is not evidence that an external action happened.
            assertTrue(store.get(AgentRunId("safe"))!!.hasUnknownAction)
        }
    }

    @Test fun uncertainOutcomeSurvivesRestartAndCannotBecomeConfirmed() = runBlocking {
        val id = AgentRunId("uncertain")
        val prepared = AgentStep.ToolInvocationPrepared(
            AgentStepId("prepared"), id, start,
            ToolInvocationId("inv-1"), "call-1", "unknown-device-action",
            """{"power":true}""", "digest", "key-1", 1, "NEVER_REPLAY"
        )
        val claim = AgentStep.ToolExecutionClaimed(
            AgentStepId("claimed"), id, start,
            ToolInvocationId("inv-1"), "call-1", "unknown-device-action",
            "key-1", "NEVER_REPLAY"
        )
        db().use { store ->
            store.create(run("uncertain"))
            assertTrue(store.appendStep(prepared))
            assertTrue(store.appendStep(claim))
        }
        db().use { store ->
            val restored = store.get(id)!!
            assertTrue(restored.hasUnknownAction)
            val plan = DefaultAgentRecoveryPlanner().plan(restored, null, CapabilitySnapshot())
            assertTrue(plan is RecoveryPlan.UncertainExternalAction,
                "Never-replay claims must not be interpreted as safe retries")
            val status = CanonicalRecoveryInspector(store).inspect().single()
            assertFalse(status.automaticReplayPermitted)
            assertEquals("UncertainExternalAction", status.planKind)
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
    @Test fun agentDbSnapshotIsConsistentAndNeverOverwritten() = runBlocking {
        val privateDir = folder.resolve("snapshots")
        java.nio.file.Files.createDirectories(privateDir)
        java.nio.file.Files.setPosixFilePermissions(privateDir, setOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
        ))
        val target = privateDir.resolve("agent-snapshot.sqlite")
        db().use { store ->
            store.create(run("snapshot", AgentRunState.SUSPENDED))
            assertTrue(store.backupTo(target) > 0)
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { store.backupTo(target) }
            }
        }
        java.sql.DriverManager.getConnection("jdbc:sqlite:${target}").use { db ->
            db.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA quick_check").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("ok", rs.getString(1))
                }
                stmt.executeQuery("SELECT COUNT(*) FROM agent_runs").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(1, rs.getInt(1))
                }
            }
        }
    }

    @Test fun resolutionRequiresCorrectPendingStateAndInputLength() = runBlocking {
        db().use { storage ->
            val id = AgentRunId("clarify")
            val reqId = AgentInputRequestId("clarify-1")
            storage.create(run("clarify", AgentRunState.WAITING_FOR_CLARIFICATION))
            assertTrue(storage.appendStep(AgentStep.InputRequested(
                AgentStepId("ask-clarification"), id, start, reqId,
                AgentInputRequest.Clarification(
                    id = reqId, runId = id, createdAt = start,
                    question = "Enter a short label", field = "label",
                    expected = ExpectedInput.Text(minLength = 2, maxLength = 5, singleLine = true)
                )
            )))
            assertFalse(storage.resolveInputIfPending(reqId, AgentInputResolution.Approved))
            assertFalse(storage.resolveInputIfPending(reqId, AgentInputResolution.Clarified("A")))
            assertFalse(storage.resolveInputIfPending(reqId, AgentInputResolution.Clarified("too-long")))
            assertFalse(storage.resolveInputIfPending(reqId,
                AgentInputResolution.Clarified("AA\nB")))
            assertTrue(storage.resolveInputIfPending(reqId, AgentInputResolution.Clarified("Halo")))
            assertFalse(storage.resolveInputIfPending(reqId, AgentInputResolution.Clarified("Again")))
        }
        db().use { restored ->
            assertEquals(2, restored.get(AgentRunId("clarify"))?.steps?.size)
        }
    }

    @Test fun providerToolCallsRoundTripWithoutGrantingExecution() = runBlocking {
        db().use { storage ->
            storage.create(run("provider-response"))
            val step = AgentStep.ProviderTurnCompleted(
                AgentStepId("provider-responded"), AgentRunId("provider-response"), start,
                ProviderTurnId("provider-turn-1"), "Model proposed a command",
                listOf(ProviderToolCallRecord("call-1", "run-command", "{ }")),
                "tool_calls"
            )
            assertTrue(storage.appendStep(step))
            val decoded = storage.get(AgentRunId("provider-response"))!!
                .steps.single() as AgentStep.ProviderTurnCompleted
            assertEquals("run-command", decoded.toolCalls.single().toolName)
            assertEquals("{ }", decoded.toolCalls.single().argumentsJson)
            assertFalse(CanonicalRecoveryInspector(storage)
                .inspect().single().automaticReplayPermitted)
        }
    }

    @Test fun runGuardAndExternalConditionRoundTripWithoutSchedulingJobs() = runBlocking {
        db().use { store ->
            val runId = AgentRunId("guarded")
            store.create(run("guarded", AgentRunState.SUSPENDED))
            val guard = AgentStep.RunGuardTriggered(
                AgentStepId("guard-stop"), runId, start,
                RunGuardReason.TOO_MANY_TOOL_ROUNDS, 12,
                null, "hash-without-content", 1530L, "Tool limit reached"
            )
            assertTrue(store.appendStep(guard))
            assertTrue(store.appendStep(AgentStep.ExternalConditionRegistered(
                AgentStepId("watch-1"), runId, start,
                "watch-id", "TIME", "timer-1", "Waiting for an event"
            )))
            assertTrue(store.appendStep(AgentStep.ExternalConditionSatisfied(
                AgentStepId("watch-2"), runId, start,
                "watch-id", "TIME", "timer-1"
            )))
            val records = store.get(runId)!!.steps
            assertEquals(3, records.size)
            assertEquals("hash-without-content",
                (records[0] as AgentStep.RunGuardTriggered).evidenceHash)
            assertEquals("timer-1",
                (records[2] as AgentStep.ExternalConditionSatisfied).targetId)
        }
    }

    @Test fun providerAndToolOutcomeRetainOriginalActionTruth() = runBlocking {
        val runId = AgentRunId("outcome")
        db().use { store ->
            store.create(run("outcome"))
            assertTrue(store.appendStep(AgentStep.ToolOutcomeRecorded(
                AgentStepId("result"), runId, start, ToolInvocationId("inv-2"),
                "call-2", "external-action", false, "No confirmation",
                ActionTruth.UNKNOWN, "connection_lost", null, null, null
            )))
            val result = store.get(runId)!!.steps.single() as AgentStep.ToolOutcomeRecorded
            assertEquals(ActionTruth.UNKNOWN, result.actionTruth)
            assertEquals("connection_lost", result.failureKind)
        }
    }

    @Test fun duplicateResponseCommitIdentityCannotBeJournaledTwice() = runBlocking {
        db().use { store ->
            store.create(run("committed"))
            val runId = AgentRunId("committed")
            val first = AgentStep.ResponseCommitted(
                AgentStepId("commit-1"), runId, start, "stable-delivery-id", "interaction-1"
            )
            assertTrue(store.appendStep(first))
            assertFalse(store.appendStep(first.copy(id = AgentStepId("commit-2"))))
            assertEquals(1, store.get(runId)!!.steps.size)
        }
    }

    @Test fun agentDatabaseRejectsRedirectedAncestorBeforeCreation() {
        val other = folder.resolve("outside")
        java.nio.file.Files.createDirectories(other)
        val link = folder.resolve("redirect")
        java.nio.file.Files.createSymbolicLink(link, other)
        assertThrows(IllegalArgumentException::class.java) {
            SqliteAgentRunStore(link.resolve("agent.db").toAbsolutePath())
        }
        assertFalse(java.nio.file.Files.exists(other.resolve("agent.db")))
        val real = folder.resolve("real")
        java.nio.file.Files.createDirectories(real)
        java.nio.file.Files.createSymbolicLink(
            real.resolve("agent.db"), other.resolve("unexpected.db")
        )
        assertThrows(IllegalArgumentException::class.java) {
            SqliteAgentRunStore(real.resolve("agent.db").toAbsolutePath())
        }
        assertFalse(java.nio.file.Files.exists(other.resolve("unexpected.db")))
    }

    @Test fun existingUnversionedAgentDbIsNeverAdoptedAsV1() {
        val path = folder.resolve("legacy-agent.sqlite").toAbsolutePath()
        java.sql.DriverManager.getConnection("jdbc:sqlite:${path}").use { db ->
            db.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE agent_runs(id TEXT PRIMARY KEY)")
                stmt.execute("INSERT INTO agent_runs(id) VALUES ('old-record')")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            SqliteAgentRunStore(path)
        }
        java.sql.DriverManager.getConnection("jdbc:sqlite:${path}").use { db ->
            db.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM agent_runs").use { row ->
                    assertTrue(row.next())
                    assertEquals(1, row.getInt(1))
                }
            }
        }
    }

}
