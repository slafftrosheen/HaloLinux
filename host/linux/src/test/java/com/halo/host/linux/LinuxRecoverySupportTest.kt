package com.halo.host.linux

import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LinuxRecoverySupportTest {
    @TempDir lateinit var tempDir: Path

    @Test fun standaloneBuildDoesNotCreateOrReplayCanonicalAgentData() = runBlocking {
        // Default build has no canonical runtime/cognition dependency.
        val recovery = LinuxRecoverySupportFactory.open(tempDir)
        recovery.use {
            val result = it.inspect()
            assertEquals("NOT_LINKED", result.getString("state"))
            assertFalse(result.getBoolean("automatic_replay"))
            assertFalse(result.getBoolean("tool_execution"))
        }
        assertFalse(java.nio.file.Files.exists(tempDir.resolve("canonical-agent-runs.db")))
    }
}
