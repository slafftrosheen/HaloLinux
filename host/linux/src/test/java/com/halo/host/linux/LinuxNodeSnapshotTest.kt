package com.halo.host.linux

import com.halo.adapters.storage.LinuxStorage
import com.halo.adapters.storage.LinuxStorageConfig
import com.halo.adapters.storage.MemoryRecord
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LinuxNodeSnapshotTest {
    @TempDir lateinit var root: Path

    private fun privateBackups(): Path =
        Files.createDirectories(root.resolve("backups")).also {
            Files.setPosixFilePermissions(it, setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
            ))
        }

    private suspend fun storage(): LinuxStorage = LinuxStorage().also {
        it.initialize(LinuxStorageConfig(
            dataDir = root.resolve("data").toString(),
            configDir = root.resolve("config").toString(),
            cacheDir = root.resolve("cache").toString(),
        ))
    }

    @Test fun modelFreeSnapshotContainsVerifiedMainDbAndManifest() = runBlocking {
        val db = storage()
        try {
            val now = Instant.now()
            db.storeMemory(MemoryRecord("memory-1", "Private offline memory",
                "test", "personal", 1.0, null, now, now, "{}"))
            val destination = privateBackups().resolve("snapshot-001")
            val receipt = LinuxNodeSnapshot.create(destination, db, DisabledRecoverySupport)
            assertTrue(receipt.getBoolean("ok"))
            assertEquals(1, receipt.getInt("files"))
            val document = JSONObject(Files.readString(destination.resolve("manifest.json")))
            assertEquals("halo-node-snapshot-v1", document.getString("format"))
            assertFalse(document.getBoolean("encrypted"))
            assertFalse(document.getBoolean("includes_canonical_agent_store"))
            val main = destination.resolve("halo-main.sqlite")
            val entry = document.getJSONArray("files").getJSONObject(0)
            val calculated = MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(main))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertEquals(calculated, entry.getString("sha256"))
            assertEquals(Files.size(main), entry.getLong("bytes"))
            java.sql.DriverManager.getConnection("jdbc:sqlite:${main}").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA quick_check").use { rows ->
                        assertTrue(rows.next())
                        assertEquals("ok", rows.getString(1))
                    }
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    LinuxNodeSnapshot.create(destination, db, DisabledRecoverySupport)
                }
            }
        } finally { db.close() }
    }

    @Test fun agentBackupFailureNeverLeavesPartialSnapshotDirectory() = runBlocking {
        val db = storage()
        val failing = object : LinuxRecoverySupport {
            override suspend fun inspect() = JSONObject().put("state", "CLASSIFICATION_ONLY")
            override suspend fun backup(destination: Path): JSONObject {
                throw IllegalStateException("simulated journal backup failure")
            }
            override fun close() = Unit
        }
        try {
            val destination = privateBackups().resolve("failed-001")
            assertThrows(IllegalStateException::class.java) {
                runBlocking { LinuxNodeSnapshot.create(destination, db, failing) }
            }
            assertFalse(Files.exists(destination))
        } finally { db.close() }
    }

    @Test fun publicBackupParentIsRejected() = runBlocking {
        val db = storage()
        try {
            val parent = Files.createDirectories(root.resolve("public"))
            Files.setPosixFilePermissions(parent, setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OTHERS_READ
            ))
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking {
                    LinuxNodeSnapshot.create(parent.resolve("snapshot"), db, DisabledRecoverySupport)
                }
            }
            assertFalse(Files.exists(parent.resolve("snapshot")))
        } finally { db.close() }
    }
}
