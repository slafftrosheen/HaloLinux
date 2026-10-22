package com.halo.host.linux

import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LinuxRecoverySupportTest {
    @TempDir lateinit var tempDir: Path

    @Test fun linkedRecoveryModeNeverEnablesAutomaticReplayOrTools() = runBlocking<Unit> {
        // Default build has no canonical runtime/cognition dependency.
        val recovery = LinuxRecoverySupportFactory.open(tempDir)
        recovery.use {
            it.prepare()
            val result = it.inspect()
            val canonicalLinked = runCatching {
                Class.forName("com.halo.host.linux.CanonicalHostRecoverySupport")
            }.isSuccess
            assertEquals(if (canonicalLinked) "CLASSIFICATION_ONLY" else "NOT_LINKED",
                result.getString("state"))
            assertFalse(result.getBoolean("automatic_replay"))
            assertFalse(result.getBoolean("tool_execution"))
        }
        // The database exists ONLY when the opt-in canonical source is compiled.
        val canonicalLinked = runCatching {
            Class.forName("com.halo.host.linux.CanonicalHostRecoverySupport")
        }.isSuccess
        assertEquals(canonicalLinked,
            java.nio.file.Files.exists(tempDir.resolve("canonical-agent-runs.db")))
    }
}
