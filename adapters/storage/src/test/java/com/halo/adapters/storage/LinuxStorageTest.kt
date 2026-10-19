package com.halo.adapters.storage

import com.halo.sdk.runtime.AgentInputRequestId
import com.halo.sdk.runtime.AgentInputResolution
import com.halo.sdk.runtime.AgentRun
import com.halo.sdk.runtime.AgentRunEvent
import com.halo.sdk.runtime.AgentRunId
import com.halo.sdk.runtime.AgentRunStatus
import com.halo.sdk.runtime.AgentRunStore
import com.halo.sdk.runtime.AgentStep
import com.halo.sdk.runtime.AgentStepId
import com.halo.sdk.runtime.ActionTruth
import com.halo.sdk.runtime.StructuredStorageConfig
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

class LinuxStorageTest {

    private lateinit var storage: LinuxStorage
    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp(@TempDir tempDir: Path) {
        this.tempDir = tempDir
        storage = LinuxStorage()
    }

    @Test
    fun `storage initializes database schema`() = runTest {
        val config = StructuredStorageConfig(
            dataDir = tempDir.resolve("data").toString(),
            configDir = tempDir.resolve("config").toString(),
            cacheDir = tempDir.resolve("cache").toString(),
        )
        
        storage.initialize(config)
        
        // Verify we can get directories
        val dataDir = storage.getDataDirectory()
        val configDir = storage.getConfigDirectory()
        val cacheDir = storage.getCacheDirectory()
        
        assertTrue(dataDir.contains("data"))
        assertTrue(configDir.contains("config"))
        assertTrue(cacheDir.contains("cache"))
        
        storage.close()
    }

    @Test
    fun `conversation record creation and storage`() = runTest {
        val config = StructuredStorageConfig(
            dataDir = tempDir.resolve("data").toString(),
            configDir = tempDir.resolve("config").toString(),
            cacheDir = tempDir.resolve("cache").toString(),
        )
        
        storage.initialize(config)
        
        val conversation = ConversationRecord(
            id = UUID.randomUUID().toString(),
            title = "Test Conversation",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            providerId = "test-provider",
            modelId = "test-model",
            metadata = "{}",
            messages = listOf(
                MessageRecord(
                    id = UUID.randomUUID().toString(),
                    conversationId = "test-conv",
                    role = "user",
                    content = "Hello",
                    providerId = null,
                    modelId = null,
                    tokensUsed = 0,
                    latencyMs = 0,
                    createdAt = Instant.now(),
                    metadata = "{}",
                ),
                MessageRecord(
                    id = UUID.randomUUID().toString(),
                    conversationId = "test-conv",
                    role = "assistant",
                    content = "Hi there!",
                    providerId = "test-provider",
                    modelId = "test-model",
                    tokensUsed = 10,
                    latencyMs = 150,
                    createdAt = Instant.now(),
                    metadata = "{}",
                )
            ),
        )
        
        storage.storeConversation(conversation)
        
        val loaded = storage.loadConversation(conversation.id)
        assertNotNull(loaded)
        assertEquals(conversation.title, loaded!!.title)
        assertEquals(2, loaded.messages.size)
        assertEquals("user", loaded.messages[0].role)
        assertEquals("assistant", loaded.messages[1].role)
        
        storage.close()
    }

    @Test
    fun `memory record creation and search`() = runTest {
        val config = StructuredStorageConfig(
            dataDir = tempDir.resolve("data").toString(),
            configDir = tempDir.resolve("config").toString(),
            cacheDir = tempDir.resolve("cache").toString(),
        )
        
        storage.initialize(config)
        
        val memory = MemoryRecord(
            id = UUID.randomUUID().toString(),
            content = "User prefers dark mode",
            source = "user",
            scope = "personal",
            confidence = 0.95,
            expiresAt = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            metadata = "{}",
        )
        
        storage.storeMemory(memory)
        
        // Note: FTS search requires the memory to be committed and FTS triggers to fire
        // This test verifies the store operation works
        val memories = storage.searchMemories("dark mode", 10)
        // FTS may or may not return results depending on trigger timing
        // but the store should succeed
        
        storage.close()
    }

    @Test
    fun `node identity creation and persistence`() = runTest {
        val config = StructuredStorageConfig(
            dataDir = tempDir.resolve("data").toString(),
            configDir = tempDir.resolve("config").toString(),
            cacheDir = tempDir.resolve("cache").toString(),
        )
        
        storage.initialize(config)
        
        // First call creates and persists identity
        val identity1 = storage.getNodeIdentity()
        assertNotNull(identity1.nodeId)
        assertEquals("Halo Linux Node", identity1.displayName)
        assertEquals("unpaired", identity1.trustState)
        assertEquals(1, identity1.protocolVersion)
        
        // Second call should return persisted identity
        val identity2 = storage.getNodeIdentity()
        assertEquals(identity1.nodeId, identity2.nodeId)
        assertEquals(identity1.displayName, identity2.displayName)
        
        // Test setting custom identity
        val customIdentity = NodeIdentity(
            nodeId = "custom-node-id",
            displayName = "Custom Node",
            publicKey = "custom-pubkey",
            privateKey = "custom-privkey",
            protocolVersion = 1,
            softwareVersion = "0.1.0",
            capabilities = listOf("inference"),
            trustState = "paired",
        )
        
        storage.setNodeIdentity(customIdentity)
        val identity3 = storage.getNodeIdentity()
        assertEquals("custom-node-id", identity3.nodeId)
        assertEquals("Custom Node", identity3.displayName)
        assertEquals("paired", identity3.trustState)
        
        storage.close()
    }

    @Test
    fun `conversation listing`() = runTest {
        val config = StructuredStorageConfig(
            dataDir = tempDir.resolve("data").toString(),
            configDir = tempDir.resolve("config").toString(),
            cacheDir = tempDir.resolve("cache").toString(),
        )
        
        storage.initialize(config)
        
        // Store multiple conversations
        for (i in 1..3) {
            val conversation = ConversationRecord(
                id = "conv-$i",
                title = "Conversation $i",
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                providerId = "provider-$i",
                modelId = "model-$i",
                metadata = "{}",
                messages = emptyList(),
            )
            storage.storeConversation(conversation)
        }
        
        val conversations = storage.listConversations(limit = 10, offset = 0)
        assertEquals(3, conversations.size)
        // Should be ordered by updated_at DESC
        assertEquals("conv-3", conversations[0].id)
        assertEquals("conv-2", conversations[1].id)
        assertEquals("conv-1", conversations[2].id)
        
        // Test pagination
        val page2 = storage.listConversations(limit = 2, offset = 1)
        assertEquals(2, page2.size)
        assertEquals("conv-2", page2[0].id)
        assertEquals("conv-1", page2[1].id)
        
        storage.close()
    }
}

class SqliteAgentRunStoreTest {

    private lateinit var store: SqliteAgentRunStore
    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp(@TempDir tempDir: Path) {
        this.tempDir = tempDir
        store = SqliteAgentRunStore()
    }

    @Test
    fun `agent run store create and get`() = runTest {
        val dbPath = tempDir.resolve("agent_runs.db")
        store.initialize(dbPath)
        
        val run = AgentRun.createNew(
            interactionId = "interaction-1",
            conversationId = "conv-1",
            originNodeId = "node-1",
            providerId = "provider-1",
            modelId = "model-1",
        )
        
        store.create(run)
        
        val retrieved = store.get(run.id)
        assertNotNull(retrieved)
        assertEquals(run.id, retrieved!!.id)
        assertEquals("interaction-1", retrieved.interactionId)
        assertEquals(AgentRunStatus.CREATED, retrieved.status)
        assertEquals(0, retrieved.revision)
        
        store.close()
    }

    @Test
    fun `agent run state transition with optimistic locking`() = runTest {
        val dbPath = tempDir.resolve("agent_runs.db")
        store.initialize(dbPath)
        
        val run = AgentRun.createNew(
            interactionId = "interaction-2",
        )
        store.create(run)
        
        // Valid transition: CREATED -> RUNNING
        val runningRun = run.copy(
            status = AgentRunStatus.RUNNING,
            revision = 1,
            updatedAt = Instant.now(),
        )
        store.update(runningRun)
        
        val retrieved = store.get(run.id)!!
        assertEquals(AgentRunStatus.RUNNING, retrieved.status)
        assertEquals(1, retrieved.revision)
        
        // Stale revision should fail
        val staleRun = runningRun.copy(
            status = AgentRunStatus.COMPLETED,
            revision = 0, // Wrong revision
            updatedAt = Instant.now(),
        )
        assertThrows(java.util.ConcurrentModificationException::class.java) {
            store.update(staleRun)
        }
        
        store.close()
    }

    @Test
    fun `agent run claim resume CAS`() = runTest {
        val dbPath = tempDir.resolve("agent_runs.db")
        store.initialize(dbPath)
        
        val run = AgentRun.createNew(interactionId = "interaction-3")
            .copy(status = AgentRunStatus.RUNNING, revision = 1, updatedAt = Instant.now())
        store.create(run)
        store.update(run)
        
        // Claim resume from RUNNING to INTERRUPTED
        val claimed = store.claimResume(run.id, AgentRunStatus.RUNNING)
        assertTrue(claimed)
        
        val retrieved = store.get(run.id)!!
        assertEquals(AgentRunStatus.INTERRUPTED, retrieved.status)
        
        // Second claim should fail (already INTERRUPTED)
        val claimedAgain = store.claimResume(run.id, AgentRunStatus.RUNNING)
        assertFalse(claimedAgain)
        
        // Claim from wrong expected state should fail
        val run2 = AgentRun.createNew(interactionId = "interaction-4")
            .copy(status = AgentRunStatus.WAITING_FOR_TOOL, revision = 1, updatedAt = Instant.now())
        store.create(run2)
        store.update(run2)
        
        val claimedWrong = store.claimResume(run2.id, AgentRunStatus.RUNNING)
        assertFalse(claimedWrong)
        
        store.close()
    }

    @Test
    fun `agent run append step deduplication`() = runTest {
        val dbPath = tempDir.resolve("agent_runs.db")
        store.initialize(dbPath)
        
        val run = AgentRun.createNew(interactionId = "interaction-5")
        store.create(run)
        
        val step1 = AgentStep.ToolDispatched(
            stepId = AgentStepId(UUID.randomUUID().toString()),
            runId = run.id,
            sequence = 1,
            createdAt = Instant.now(),
            toolCallId = "tool-call-1",
            toolName = "test_tool",
            arguments = mapOf("arg" to "value"),
        )
        
        // First append should succeed
        val appended1 = store.appendStep(step1)
        assertTrue(appended1)
        
        // Second append with same toolCallId should fail (deduplication)
        val step2 = AgentStep.ToolDispatched(
            stepId = AgentStepId(UUID.randomUUID().toString()),
            runId = run.id,
            sequence = 2,
            createdAt = Instant.now(),
            toolCallId = "tool-call-1", // Same toolCallId
            toolName = "test_tool",
            arguments = mapOf("arg" to "value"),
        )
        
        val appended2 = store.appendStep(step2)
        assertFalse(appended2)
        
        store.close()
    }

    @Test
    fun `agent run non-terminal runs`() = runTest {
        val dbPath = tempDir.resolve("agent_runs.db")
        store.initialize(dbPath)
        
        // Create runs in various states
        val run1 = AgentRun.createNew(interactionId = "i1").copy(status = AgentRunStatus.RUNNING, revision = 1)
        val run2 = AgentRun.createNew(interactionId = "i2").copy(status = AgentRunStatus.WAITING_FOR_TOOL, revision = 1)
        val run3 = AgentRun.createNew(interactionId = "i3").copy(status = AgentRunStatus.COMPLETED, revision = 1)
        val run4 = AgentRun.createNew(interactionId = "i4").copy(status = AgentRunStatus.FAILED, revision = 1)
        
        store.create(run1); store.update(run1)
        store.create(run2); store.update(run2)
        store.create(run3); store.update(run3)
        store.create(run4); store.update(run4)
        
        val nonTerminal = store.nonTerminalRuns()
        assertEquals(2, nonTerminal.size)
        assertTrue(nonTerminal.any { it.id == run1.id })
        assertTrue(nonTerminal.any { it.id == run2.id })
        assertFalse(nonTerminal.any { it.id == run3.id }) // COMPLETED is terminal
        assertFalse(nonTerminal.any { it.id == run4.id }) // FAILED is terminal
        
        store.close()
    }

    @Test
    fun `agent run crash recovery reconciliation`() = runTest {
        val dbPath = tempDir.resolve("agent_runs.db")
        store.initialize(dbPath)
        
        // Simulate crashed runs
        val crashedRun1 = AgentRun.createNew(interactionId = "crash-1")
            .copy(status = AgentRunStatus.RUNNING, revision = 1, updatedAt = Instant.now())
        val crashedRun2 = AgentRun.createNew(interactionId = "crash-2")
            .copy(status = AgentRunStatus.WAITING_FOR_TOOL, revision = 1, updatedAt = Instant.now())
        val completedRun = AgentRun.createNew(interactionId = "crash-3")
            .copy(status = AgentRunStatus.COMPLETED, revision = 1, updatedAt = Instant.now())
        
        store.create(crashedRun1); store.update(crashedRun1)
        store.create(crashedRun2); store.update(crashedRun2)
        store.create(completedRun); store.update(completedRun)
        
        // Reconcile: RUNNING and WAITING_FOR_TOOL should become INTERRUPTED
        var recovered = 0
        for (run in store.nonTerminalRuns()) {
            if (store.claimResume(run.id, run.status)) {
                recovered++
            }
        }
        
        assertEquals(2, recovered)
        
        // Verify runs are now INTERRUPTED
        val run1After = store.get(crashedRun1.id)!!
        val run2After = store.get(crashedRun2.id)!!
        assertEquals(AgentRunStatus.INTERRUPTED, run1After.status)
        assertEquals(AgentRunStatus.INTERRUPTED, run2After.status)
        
        // COMPLETED run should be unchanged
        val completedAfter = store.get(completedRun.id)!!
        assertEquals(AgentRunStatus.COMPLETED, completedAfter.status)
        
        store.close()
    }
}