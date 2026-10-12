package com.halo.host.linux

import com.halo.adapters.storage.LinuxStorage
import com.halo.adapters.storage.LinuxStorageConfig
import com.halo.adapters.storage.MemoryRecord
import com.halo.sdk.runtime.Query
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LinuxKnowledgeQueriesTest {
    @TempDir lateinit var tempDir: Path

    @Test fun readOnlyQueryBusRetrievesOnlyExplicitLocalNotes() = runBlocking {
        val storage = LinuxStorage()
        storage.initialize(LinuxStorageConfig(
            dataDir=tempDir.resolve("data").toString(),
            configDir=tempDir.resolve("config").toString(),
            cacheDir=tempDir.resolve("cache").toString()
        ))
        try {
            val now = Instant.now()
            storage.storeMemory(MemoryRecord(
                "note-1", "Pi5 memory-only Halo node", "test", "personal",
                1.0, null, now, now, "{}"
            ))
            val bridge = LinuxRuntimeBridge(storage)
            bridge.start()
            try {
                val found = bridge.query(SearchLocalMemory("Pi5"))
                assertEquals("note-1", found.single().id)
                assertEquals(0L, bridge.query(CountInterruptedTextTurns))
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { bridge.query(SearchLocalMemory("*", limit=0)) }
                }
                val unknown = object : Query<Int> {}
                assertThrows(UnsupportedOperationException::class.java) {
                    runBlocking { bridge.query(unknown) }
                }
            } finally { bridge.stop() }
        } finally { storage.close() }
    }
}
