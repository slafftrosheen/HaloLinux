package com.halo.host.linux

import com.halo.adapters.inference.FixtureInferenceProvider
import com.halo.adapters.storage.LinuxStorage
import com.halo.adapters.storage.LinuxStorageConfig
import com.halo.linux.agent.SqliteAgentRunStore
import com.halo.runtime.cognition.agent.AgentRunId
import com.halo.runtime.cognition.agent.AgentRunState
import com.halo.runtime.cognition.agent.AgentStep
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Executable only in the SHA-pinned canonical cognition source build. */
class CanonicalTextTurnIntegrationTest {
    @TempDir lateinit var folder: Path

    private suspend fun storage(): LinuxStorage = LinuxStorage().also {
        it.initialize(LinuxStorageConfig(
            dataDir = folder.resolve("data").toString(),
            configDir = folder.resolve("config").toString(),
            cacheDir = folder.resolve("cache").toString(),
        ))
    }

    @Test fun successfulTextTurnUsesRealAgentCoordinatorAndDurableJournal() = runBlocking<Unit> {
        val db = storage()
        val runtime = LinuxRuntimeBridge(db)
        val provider = LinuxProviderLifecycle(FixtureInferenceProvider(), "fixture-model")
        val canonical = CanonicalHostRecoverySupport(folder.resolve("data"))
        try {
            canonical.prepare()
            runtime.start()
            val response = LinuxCognitiveTurn(provider, db, runtime,
                recoverySupport = { canonical }).ask("Halo's private local test")
            assertEquals("COMMITTED", response.canonicalJournalState)
            assertNotNull(response.agentRunId)
            assertEquals(1L, db.checkDatabase().conversationCount)
            assertEquals(response.text,
                db.loadConversation(response.conversationId)?.messages?.last()?.content)
            SqliteAgentRunStore(folder.resolve("data/canonical-agent-runs.db")).use { journal ->
                val persisted = journal.get(AgentRunId(response.agentRunId!!))
                assertNotNull(persisted)
                assertEquals(AgentRunState.COMPLETED, persisted!!.state)
                assertTrue(persisted.steps.any { it is AgentStep.RunPrepared })
                assertTrue(persisted.steps.any { it is AgentStep.ProviderTurnStarted })
                assertTrue(persisted.steps.any { it is AgentStep.ProviderTurnCompleted })
                assertTrue(persisted.steps.any { it is AgentStep.ResponseProduced })
                assertTrue(persisted.steps.any { it is AgentStep.ResponseCommitted })
                assertFalse(persisted.steps.any { it is AgentStep.ToolExecutionClaimed })
            }
        } finally {
            provider.close()
            canonical.close()
            runtime.stop()
            db.close()
        }
    }

    @Test fun unavailableProviderDoesNotFabricateSuccessfulRun() = runBlocking<Unit> {
        val db = storage()
        val runtime = LinuxRuntimeBridge(db)
        val provider = LinuxProviderLifecycle(null, null)
        val canonical = CanonicalHostRecoverySupport(folder.resolve("data"))
        try {
            canonical.prepare()
            runtime.start()
            assertThrows(Exception::class.java) {
                runBlocking {
                    LinuxCognitiveTurn(provider, db, runtime,
                        recoverySupport = { canonical }).ask("Unconfigured model")
                }
            }
            assertEquals(0L, db.checkDatabase().conversationCount)
            SqliteAgentRunStore(folder.resolve("data/canonical-agent-runs.db")).use { journal ->
                val runs = journal.observeRuns().first()
                assertEquals(1, runs.size)
                assertEquals(AgentRunState.FAILED, runs.single().state)
                assertFalse(runs.single().steps.any { it is AgentStep.ResponseCommitted })
            }
        } finally {
            provider.close()
            canonical.close()
            runtime.stop()
            db.close()
        }
    }
}
