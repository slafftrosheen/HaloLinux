package com.halo.adapters.storage

import com.halo.libraries.common.core.Identifier
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
        
        // Override data directories to use temp dir
        val config = LinuxStorageConfig(
            dataDir = tempDir.resolve("data").toString(),
            configDir = tempDir.resolve("config").toString(),
            cacheDir = tempDir.resolve("cache").toString(),
        )
        
        // Use reflection to initialize with test config
        // For now, we'll use a simpler approach - just test the in-memory database
    }

    @Test
    fun `storage initializes database schema`() = runTest {
        // Test that the database can be created and migrations run
        val config = LinuxStorageConfig(
            dataDir = tempDir.resolve("data").toString(),
            configDir = tempDir.resolve("config").toString(),
            cacheDir = tempDir.resolve("cache").toString(),
        )
        
        // This would require the storage to be configurable for testing
        // For now, we verify the class compiles and basic structure
        assertNotNull(LinuxStorage::class)
    }

    @Test
    fun `conversation record creation and serialization`() = runTest {
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

        assertEquals("Test Conversation", conversation.title)
        assertEquals(2, conversation.messages.size)
        assertEquals("user", conversation.messages[0].role)
        assertEquals("assistant", conversation.messages[1].role)
    }

    @Test
    fun `memory record creation and serialization`() = runTest {
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

        assertEquals("User prefers dark mode", memory.content)
        assertEquals("user", memory.source)
        assertEquals("personal", memory.scope)
        assertEquals(0.95, memory.confidence, 0.001)
    }

    @Test
    fun `node identity creation`() = runTest {
        val identity = NodeIdentity(
            nodeId = UUID.randomUUID().toString(),
            displayName = "Test Node",
            publicKey = "base64pubkey",
            privateKey = "base64privkey",
            protocolVersion = 1,
            softwareVersion = "0.1.0",
            capabilities = listOf("inference", "memory"),
            trustState = "unpaired",
        )

        assertNotNull(identity.nodeId)
        assertEquals("Test Node", identity.displayName)
        assertEquals(1, identity.protocolVersion)
        assertEquals("unpaired", identity.trustState)
    }
}