package com.halo.host.linux

import com.halo.adapters.inference.FixtureInferenceProvider
import com.halo.adapters.storage.LinuxStorage
import com.halo.adapters.storage.LinuxStorageConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class LinuxCognitiveTurnTest {
    @TempDir lateinit var folder: Path

    @Test fun textTurnIsDurableAndContentIsNotNeededForStatus() = runBlocking {
        val storage = LinuxStorage()
        storage.initialize(LinuxStorageConfig(
            dataDir=folder.resolve("data").toString(),
            configDir=folder.resolve("config").toString(),
            cacheDir=folder.resolve("cache").toString()
        ))
        val runtime = LinuxRuntimeBridge()
        val provider = LinuxProviderLifecycle(FixtureInferenceProvider(), "fixture-model")
        try {
            runtime.start()
            val result = LinuxCognitiveTurn(provider, storage, runtime).ask("Hello Halo")
            assertTrue(result.text.isNotBlank())
            assertTrue(result.interactionId.isNotBlank())
            val restored = storage.loadConversation(result.conversationId)
            assertEquals("Hello Halo", restored?.messages?.first()?.content)
            assertEquals(result.text, restored?.messages?.last()?.content)
            assertEquals(1L, storage.checkDatabase().conversationCount)
        } finally {
            provider.close()
            runtime.stop()
            storage.close()
        }
    }

    @Test fun unconfiguredInferenceDoesNotPersistFabricatedConversation() = runBlocking {
        val storage = LinuxStorage()
        storage.initialize(LinuxStorageConfig(
            dataDir=folder.resolve("data2").toString(),
            configDir=folder.resolve("config2").toString(),
            cacheDir=folder.resolve("cache2").toString()
        ))
        val runtime = LinuxRuntimeBridge()
        try {
            runtime.start()
            assertThrows(Exception::class.java) {
                runBlocking {
                    LinuxCognitiveTurn(LinuxProviderLifecycle(null, null), storage, runtime)
                        .ask("Do not invent an answer")
                }
            }
            assertEquals(0L, storage.checkDatabase().conversationCount)
        } finally {
            runtime.stop()
            storage.close()
        }
    }
}
