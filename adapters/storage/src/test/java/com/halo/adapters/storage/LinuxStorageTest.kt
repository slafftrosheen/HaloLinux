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
    @Test fun plainTextSearchAndDeletion() = runTest {
        val db = open()
        val timestamp = Instant.now()
        db.storeMemory(MemoryRecord("delete-me", "C++ and Raspberry Pi 5",
            "test","personal",1.0,null,timestamp,timestamp,"{}"))
        assertEquals(1, db.searchMemories("Raspberry Pi").size)
        assertEquals(1, db.searchMemories("C++").size)
        assertTrue(db.deleteMemory("delete-me"))
        assertTrue(db.searchMemories("Raspberry").isEmpty())
        assertFalse(db.deleteMemory("delete-me"))
        db.close()
    }

    @Test fun backupIsConsistentAndNeverOverwrites() = runTest {
        val original = open()
        val now = Instant.now()
        original.storeMemory(MemoryRecord(
            "backup-test", "Backup search content", "test", "personal",
            1.0, null, now, now, "{}"
        ))
        val state = original.checkDatabase()
        assertTrue(state.integrityOk, state.integrityMessage)
        assertTrue(state.schemaVersion >= 2)
        assertEquals(1L, state.memoryCount)
        val file = tempDir.resolve("backup.db")
        val backup = original.createBackup(file)
        assertTrue(backup.bytes > 0)
        assertEquals(file.toString(), backup.path)
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { original.createBackup(file) }
        }
        original.close()
        val copy = LinuxStorage()
        copy.initialize(LinuxStorageConfig(
            dataDir=tempDir.resolve("restore").toString(),
            configDir=tempDir.resolve("restore-config").toString(),
            cacheDir=tempDir.resolve("restore-cache").toString()
        ))
        copy.close()
        // Verify the VACUUM snapshot independently through its own SQLite connection.
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT content FROM memories WHERE id='backup-test'").use { result ->
                    assertTrue(result.next())
                    assertEquals("Backup search content", result.getString(1))
                }
            }
        }
    }

    @Test fun backupDoesNotAllowRelativePaths() = runTest {
        val storage = open()
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { storage.createBackup(Path.of("relative.db")) }
        }
        storage.close()
    }

    @Test fun interruptedTurnNeverAutomaticallyReexecutes() = runTest {
        val store = open()
        store.beginTurn("one")
        store.markTurn("one", "REASONING")
        store.beginTurn("two")
        assertEquals(2, store.reconcileInterruptedTurns())
        assertEquals(2L, store.countInterruptedTurns())
        assertEquals(0, store.reconcileInterruptedTurns())
        store.close()
        val reopened = open()
        assertEquals(2L, reopened.countInterruptedTurns())
        assertEquals(0L, reopened.checkDatabase().conversationCount)
        reopened.close()
    }

    @Test fun terminalTurnCannotBeCommittedTwice() = runTest {
        val store = open()
        store.beginTurn("replay")
        store.markTurn("replay", "REASONING")
        store.markTurn("replay", "COMMITTED", conversationId = "stored")
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { store.markTurn("replay", "COMMITTED", conversationId = "duplicate") }
        }
        assertEquals(0L, store.countInterruptedTurns())
        store.close()
    }

    @Test fun acceptedTurnMayFailWithoutEnteringReasoning() = runTest {
        val store = open()
        store.beginTurn("provider-not-ready")
        store.markTurn("provider-not-ready", "FAILED", failureType = "PROVIDER_UNAVAILABLE")
        assertEquals(0, store.reconcileInterruptedTurns())
        assertEquals(0L, store.countInterruptedTurns())
        store.close()
    }

    @Test fun completionIsAtomicAndDuplicateCannotClobberResult() = runTest {
        val store = open()
        val now = Instant.now()
        val first = ConversationRecord(
            "atomic-1", "Atomic", now, now, "fixture", "m1", "{}",
            listOf(MessageRecord("message-1", "atomic-1", "assistant", "original",
                "fixture", "m1", 0L, 0L, now, "{}"))
        )
        store.beginTurn("turn-atomic")
        store.markTurn("turn-atomic", "REASONING")
        store.commitTextTurn("turn-atomic", first)
        assertEquals("COMMITTED", store.getTextTurnState("turn-atomic")?.state)
        assertEquals("atomic-1", store.getTextTurnState("turn-atomic")?.conversationId)
        val altered = first.copy(messages = listOf(first.messages.first().copy(content = "overwritten")))
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { store.commitTextTurn("turn-atomic", altered) }
        }
        assertEquals("original", store.loadConversation("atomic-1")?.messages?.first()?.content)
        assertEquals(1L, store.checkDatabase().conversationCount)
        store.close()
    }

    @Test fun failedConversationInsertRollsBackCommitState() = runTest {
        val store = open()
        val now = Instant.now()
        store.beginTurn("turn-bad")
        store.markTurn("turn-bad", "REASONING")
        // Two messages with the same PRIMARY KEY cause a real SQLite failure.
        // Both the conversation INSERT and the COMMITTED state update must roll back.
        val first = MessageRecord("duplicate-msg", "bad-conversation", "user",
            "Message one", null, null, 0L, 0L, now, "{}")
        val invalid = ConversationRecord(
            "bad-conversation", "Invalid", now, now, null, null, "{}",
            listOf(first, first.copy(content = "Message two"))
        )
        assertThrows(java.sql.SQLException::class.java) {
            kotlinx.coroutines.runBlocking { store.commitTextTurn("turn-bad", invalid) }
        }
        assertEquals("REASONING", store.getTextTurnState("turn-bad")?.state)
        assertNull(store.loadConversation("bad-conversation"))
        assertEquals(0L, store.checkDatabase().conversationCount)
        store.close()
    }

    @Test fun expiredMemoriesAreNotReturnedEvenWhenTheyRankHigher() = runTest {
        val storage = open()
        val now = Instant.now()
        try {
            storage.storeMemory(MemoryRecord(
                "expired", "Halo private expiretest note", "test", "personal",
                1.0, now.minusSeconds(120), now.minusSeconds(360),
                now.minusSeconds(360), "{}"
            ))
            storage.storeMemory(MemoryRecord(
                "active", "Halo expiretest note for next week", "test", "personal",
                1.0, now.plusSeconds(86400), now, now, "{}"
            ))
            val hits = storage.searchMemories("expiretest", 10)
            assertEquals(listOf("active"), hits.map { it.id })
            assertEquals(2L, storage.checkDatabase().memoryCount)
        } finally {
            storage.close()
        }
    }

}
