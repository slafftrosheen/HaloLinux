package com.halo.adapters.storage

import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LinuxStorageTest {
    @TempDir lateinit var tempDir: Path

    private suspend fun open(): LinuxStorage = LinuxStorage().also {
        it.initialize(LinuxStorageConfig(
            dataDir=tempDir.resolve("data").toString(),
            configDir=tempDir.resolve("config").toString(),
            cacheDir=tempDir.resolve("cache").toString(),
        ))
    }

    @Test fun createsSchemaAndPersistsMemories() = runTest {
        val a = open()
        val memory = MemoryRecord(
            id="m-1", content="The Raspberry Pi stores local notes",
            source="test", scope="personal", confidence=1.0,
            expiresAt=null, createdAt=Instant.now(), updatedAt=Instant.now(), metadata="{}"
        )
        a.storeMemory(memory)
        assertEquals("m-1", a.searchMemories("Raspberry").single().id)
        a.close()
        val b = open()
        assertEquals("m-1", b.searchMemories("Raspberry").single().id)
        b.close()
    }

    @Test fun repeatedMigrationsAreIdempotent() = runTest {
        open().close()
        open().close()
        assertTrue(java.nio.file.Files.isRegularFile(tempDir.resolve("data/halo.db")))
    }

    @Test fun conversationsSurviveRestart() = runTest {
        val now = Instant.now()
        val message = MessageRecord("turn-1","conv-1","user","Testing",null,null,0L,0L,now,"{}")
        val a = open()
        a.storeConversation(ConversationRecord("conv-1","Test",now,now,null,null,"{}", listOf(message)))
        a.close()
        val b = open()
        assertEquals("Testing",b.loadConversation("conv-1")?.messages?.single()?.content)
        b.close()
    }
}
