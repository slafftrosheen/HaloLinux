package com.halo.adapters.storage

import java.nio.file.Path
import java.nio.file.Files
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

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

    @Test fun symlinkedStorageRootsAndDatabaseFilesAreRejected() = runTest {
        val outside = tempDir.resolve("outside")
        java.nio.file.Files.createDirectories(outside)
        val link = tempDir.resolve("linked-data")
        java.nio.file.Files.createSymbolicLink(link, outside)
        val config = tempDir.resolve("config")
        val cache = tempDir.resolve("cache")
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                LinuxStorage().initialize(LinuxStorageConfig(
                    dataDir=link.toString(), configDir=config.toString(), cacheDir=cache.toString()
                ))
            }
        }
        assertFalse(java.nio.file.Files.exists(outside.resolve("halo.db")))
        val real = tempDir.resolve("real")
        java.nio.file.Files.createDirectories(real)
        java.nio.file.Files.createSymbolicLink(real.resolve("halo.db"), outside.resolve("unexpected.db"))
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                LinuxStorage().initialize(LinuxStorageConfig(
                    dataDir=real.toString(), configDir=config.toString(), cacheDir=cache.toString()
                ))
            }
        }
        assertFalse(java.nio.file.Files.exists(outside.resolve("unexpected.db")))
    }

    @Test fun relativeStorageRootsFailBeforeAnyDatabaseCreation() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                LinuxStorage().initialize(LinuxStorageConfig(
                    dataDir="relative-private-data", configDir=tempDir.resolve("config").toString(),
                    cacheDir=tempDir.resolve("cache").toString()
                ))
            }
        }
    }

    @Test fun newerMainDatabaseSchemaFailsClosedWithoutDowngrade() = runTest {
        open().close()
        val path = tempDir.resolve("data/halo.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${path}").use { db ->
            db.prepareStatement(
                "INSERT INTO schema_version(version,applied_at,description) VALUES (999,?,?)"
            ).use { stmt ->
                stmt.setString(1, Instant.now().toString())
                stmt.setString(2, "future test")
                stmt.executeUpdate()
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { open() }
        }
        java.sql.DriverManager.getConnection("jdbc:sqlite:${path}").use { db ->
            db.createStatement().use { stmt ->
                stmt.executeQuery("SELECT MAX(version) FROM schema_version").use { row ->
                    assertTrue(row.next())
                    assertEquals(999, row.getInt(1))
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["DELETE", "WAL"])
    fun legacyMasterVersionThreeIsRejectedBeforeChangingDatabaseOrFilesystem(journalMode: String) = runTest {
        val storage = open()
        val now = Instant.now()
        storage.storeMemory(MemoryRecord(
            "preserve-memory", "Existing private note", "test", "personal",
            1.0, null, now, now, "{}"
        ))
        storage.close()
        val path = tempDir.resolve("data/halo.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode = $journalMode")
                stmt.execute("DROP TABLE turn_checkpoints")
                // master f975c97 reused migration 3 for this incompatible journal.
                stmt.execute("""
                    CREATE TABLE agent_runs (
                        run_id TEXT PRIMARY KEY, interaction_id TEXT NOT NULL,
                        conversation_id TEXT, origin_node_id TEXT, status TEXT NOT NULL,
                        attempt INTEGER NOT NULL DEFAULT 0 CHECK(attempt >= 0),
                        revision INTEGER NOT NULL DEFAULT 0 CHECK(revision >= 0),
                        checkpoint_ref TEXT, provider_id TEXT, model_id TEXT,
                        failure_category TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                    )
                """.trimIndent())
                stmt.execute("""
                    CREATE TABLE agent_run_events (
                        event_id TEXT PRIMARY KEY,
                        run_id TEXT NOT NULL REFERENCES agent_runs(run_id) ON DELETE CASCADE,
                        sequence INTEGER NOT NULL, event_type TEXT NOT NULL,
                        schema_version INTEGER NOT NULL DEFAULT 1, payload_ref TEXT,
                        correlation_id TEXT, created_at TEXT NOT NULL, UNIQUE(run_id, sequence)
                    )
                """.trimIndent())
                stmt.execute("""
                    INSERT INTO agent_runs(run_id,interaction_id,status,created_at,updated_at)
                    VALUES ('legacy-run','legacy-turn','INTERRUPTED','2026-10-19T00:00:00Z','2026-10-19T00:00:00Z')
                """.trimIndent())
                stmt.execute("""
                    INSERT INTO agent_run_events(event_id,run_id,sequence,event_type,created_at)
                    VALUES ('legacy-event','legacy-run',1,'INTERRUPTED','2026-10-19T00:00:00Z')
                """.trimIndent())
                stmt.execute("UPDATE schema_version SET description='Add agent runs and event journal for crash recovery' WHERE version=3")
            }
        }
        val originalBytes = Files.readAllBytes(path)
        val originalModified = Files.getLastModifiedTime(path)
        val dataPermissions = java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")
        val dbPermissions = java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--")
        Files.setPosixFilePermissions(path.parent, dataPermissions)
        Files.setPosixFilePermissions(path, dbPermissions)
        val originalEntries = Files.list(path.parent).use { entries ->
            entries.map { it.fileName.toString() }.toList().toSet()
        }
        assertFalse(Files.exists(path.resolveSibling("halo.db-wal")))
        assertFalse(Files.exists(path.resolveSibling("halo.db-shm")))
        val newConfig = tempDir.resolve("not-created-config")
        val newCache = tempDir.resolve("not-created-cache")

        val failure = assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                LinuxStorage().initialize(LinuxStorageConfig(
                    dataDir=path.parent.toString(), configDir=newConfig.toString(),
                    cacheDir=newCache.toString()
                ))
            }
        }
        assertTrue(failure.message.orEmpty().contains("legacy master"))
        assertTrue(failure.message.orEmpty().contains("back up"))
        assertTrue(failure.message.orEmpty().contains("migration"))
        assertArrayEquals(originalBytes, Files.readAllBytes(path))
        assertEquals(originalModified, Files.getLastModifiedTime(path))
        assertEquals(dataPermissions, Files.getPosixFilePermissions(path.parent))
        assertEquals(dbPermissions, Files.getPosixFilePermissions(path))
        assertEquals(originalEntries, Files.list(path.parent).use { entries ->
            entries.map { it.fileName.toString() }.toList().toSet()
        })
        assertFalse(Files.exists(newConfig))
        assertFalse(Files.exists(newCache))
        assertFalse(Files.exists(path.resolveSibling("halo.db-wal")))
        assertFalse(Files.exists(path.resolveSibling("halo.db-shm")))
        java.sql.DriverManager.getConnection("jdbc:sqlite:${path.toUri()}?mode=ro").use { db ->
            db.createStatement().use { stmt ->
                stmt.executeQuery("SELECT content FROM memories WHERE id='preserve-memory'").use { row ->
                    assertTrue(row.next())
                    assertEquals("Existing private note", row.getString(1))
                }
                stmt.executeQuery("SELECT event_id FROM agent_run_events WHERE run_id='legacy-run'").use { row ->
                    assertTrue(row.next())
                    assertEquals("legacy-event", row.getString(1))
                }
            }
        }
    }

    @Test fun legacySchemaCommittedOnlyInWalIsInspectedWithoutChangingSourceFiles() = runTest {
        open().close()
        val path = tempDir.resolve("data/halo.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { writer ->
            writer.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode = WAL")
                stmt.execute("PRAGMA wal_autocheckpoint = 0")
                stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)")
            }
            val checkpointedBytes = Files.readAllBytes(path)
            writer.autoCommit = false
            writer.createStatement().use { stmt ->
                stmt.execute("DROP TABLE turn_checkpoints")
                stmt.execute("CREATE TABLE agent_runs(run_id TEXT PRIMARY KEY,status TEXT)")
                stmt.execute("CREATE TABLE agent_run_events(event_id TEXT PRIMARY KEY,run_id TEXT)")
                stmt.execute("INSERT INTO agent_runs VALUES('preserve-wal-run','INTERRUPTED')")
                stmt.execute("INSERT INTO agent_run_events VALUES('preserve-wal-event','preserve-wal-run')")
                stmt.execute("UPDATE schema_version SET description='Add agent runs and event journal for crash recovery' WHERE version=3")
            }
            writer.commit()
            writer.autoCommit = true
            // Keep the writer connection open: committed schema and data are
            // present only in WAL, not in the checkpointed main database.
            assertTrue(Files.size(path.resolveSibling("halo.db-wal")) > 0)
            assertArrayEquals(checkpointedBytes, Files.readAllBytes(path))
            writer.createStatement().use { stmt ->
                stmt.executeQuery("SELECT event_id FROM agent_run_events").use { row ->
                    assertTrue(row.next())
                    assertEquals("preserve-wal-event", row.getString(1))
                }
            }
            val before = databaseFileState(path)
            val failure = assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { open() }
            }
            assertTrue(failure.message.orEmpty().contains("legacy master"))
            assertEquals(before, databaseFileState(path))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["-wal", "-shm", "-journal"])
    fun symlinkedDatabaseSidecarsAreRejectedBeforeOpeningThem(suffix: String) = runTest {
        open().close()
        val path = tempDir.resolve("data/halo.db")
        val outside = tempDir.resolve("outside-sidecar")
        Files.writeString(outside, "do not follow or overwrite")
        val original = Files.readAllBytes(path)
        val link = path.resolveSibling("halo.db$suffix")
        Files.createSymbolicLink(link, outside)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { open() }
        }
        assertTrue(failure.message.orEmpty().contains("symbolic links"))
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("do not follow or overwrite", Files.readString(outside))
        assertArrayEquals(original, Files.readAllBytes(path))
    }

    @Test fun existingRollbackJournalIsPreservedAndRequiresExplicitRecovery() = runTest {
        open().close()
        val path = tempDir.resolve("data/halo.db")
        Files.writeString(path.resolveSibling("halo.db-journal"), "preserve rollback evidence")
        val before = databaseFileState(path)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { open() }
        }
        assertTrue(failure.message.orEmpty().contains("rollback journal"))
        assertEquals(before, databaseFileState(path))
    }

    @ParameterizedTest
    @ValueSource(strings = ["-wal", "-shm", "-journal"])
    fun orphanDatabaseSidecarsPreventNewDatabaseCreation(suffix: String) = runTest {
        val path = tempDir.resolve("data/halo.db")
        Files.createDirectories(path.parent)
        Files.writeString(path.resolveSibling("halo.db$suffix"), "preserve orphan recovery evidence")
        val before = databaseFileState(path)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { open() }
        }
        assertTrue(failure.message.orEmpty().contains("orphan"))
        assertTrue(failure.message.orEmpty().contains("back up"))
        assertEquals(before, databaseFileState(path))
        assertFalse(Files.exists(path))
        assertFalse(Files.exists(tempDir.resolve("config")))
        assertFalse(Files.exists(tempDir.resolve("cache")))
    }

    @ParameterizedTest
    @ValueSource(strings = ["-wal", "-shm", "-journal"])
    fun orphanSidecarSymlinksPreventNewDatabaseCreation(suffix: String) = runTest {
        val path = tempDir.resolve("data/halo.db")
        Files.createDirectories(path.parent)
        val outside = tempDir.resolve("orphan-target")
        Files.writeString(outside, "do not follow orphan sidecar")
        val link = path.resolveSibling("halo.db$suffix")
        Files.createSymbolicLink(link, outside)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { open() }
        }
        assertTrue(failure.message.orEmpty().contains("symbolic links"))
        assertTrue(failure.message.orEmpty().contains("back up"))
        assertTrue(Files.isSymbolicLink(link))
        assertEquals("do not follow orphan sidecar", Files.readString(outside))
        assertFalse(Files.exists(path))
        assertFalse(Files.exists(tempDir.resolve("config")))
        assertFalse(Files.exists(tempDir.resolve("cache")))
    }

    private data class DatabaseFileState(
        val content: List<Byte>,
        val modified: java.nio.file.attribute.FileTime,
        val permissions: Set<java.nio.file.attribute.PosixFilePermission>,
    )

    private fun databaseFileState(path: Path): Map<String, DatabaseFileState> =
        Files.list(path.parent).use { entries ->
            entries.filter { it.fileName.toString().startsWith("halo.db") }.toList().associate {
                it.fileName.toString() to DatabaseFileState(
                    Files.readAllBytes(it).toList(), Files.getLastModifiedTime(it), Files.getPosixFilePermissions(it)
                )
            }
        }

    @Test fun recordedVersionThreeWithMissingCheckpointTableIsRejectedWithoutRepair() = runTest {
        open().close()
        val path = tempDir.resolve("data/halo.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode = DELETE")
                stmt.execute("DROP TABLE turn_checkpoints")
            }
        }
        val original = java.nio.file.Files.readAllBytes(path)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { open() }
        }
        assertTrue(failure.message.orEmpty().contains("turn_checkpoints"))
        assertArrayEquals(original, java.nio.file.Files.readAllBytes(path))
    }

    @Test fun recordedVersionThreeWithMalformedCheckpointTableIsRejectedWithoutRepair() = runTest {
        open().close()
        val path = tempDir.resolve("data/halo.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode = DELETE")
                stmt.execute("DROP TABLE turn_checkpoints")
                stmt.execute("CREATE TABLE turn_checkpoints (interaction_id TEXT PRIMARY KEY)")
            }
        }
        val original = java.nio.file.Files.readAllBytes(path)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { open() }
        }
        assertTrue(failure.message.orEmpty().contains("turn_checkpoints"))
        assertArrayEquals(original, java.nio.file.Files.readAllBytes(path))
    }

    @Test fun mainVersionTwoStillUpgradesWithoutLosingStoredContent() = runTest {
        val original = open()
        val now = Instant.now()
        original.storeMemory(MemoryRecord(
            "upgrade-memory", "Preserved during upgrade", "test", "personal",
            1.0, null, now, now, "{}"
        ))
        original.close()
        val path = tempDir.resolve("data/halo.db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { db ->
            db.createStatement().use { stmt ->
                stmt.execute("DROP TABLE turn_checkpoints")
                stmt.execute("DELETE FROM schema_version WHERE version=3")
            }
        }
        val upgraded = open()
        try {
            assertEquals("upgrade-memory", upgraded.searchMemories("Preserved").single().id)
            assertEquals(3, upgraded.checkDatabase().schemaVersion)
            upgraded.beginTurn("after-upgrade")
            assertEquals("ACCEPTED", upgraded.getTextTurnState("after-upgrade")?.state)
        } finally {
            upgraded.close()
        }
    }

}
