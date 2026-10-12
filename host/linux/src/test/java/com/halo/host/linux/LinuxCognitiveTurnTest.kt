package com.halo.host.linux

import com.halo.adapters.inference.FixtureInferenceProvider
import com.halo.adapters.storage.LinuxStorage
import com.halo.adapters.storage.LinuxStorageConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import com.halo.sdk.runtime.CognitiveRequestStarted
import com.halo.sdk.runtime.CognitiveReasoningStarted
import com.halo.sdk.runtime.InferenceObserved
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
        val runtime = LinuxRuntimeBridge(storage)
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
        val runtime = LinuxRuntimeBridge(storage)
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
    @Test fun canonicalCognitiveEventsAreContentFree() = runBlocking {
        val storage = LinuxStorage()
        storage.initialize(LinuxStorageConfig(
            dataDir=folder.resolve("events-data").toString(),
            configDir=folder.resolve("events-config").toString(),
            cacheDir=folder.resolve("events-cache").toString()
        ))
        val bridge = LinuxRuntimeBridge(storage)
        val provider = LinuxProviderLifecycle(FixtureInferenceProvider(), "fixture-model")
        val observed = mutableListOf<com.halo.sdk.runtime.HaloEvent>()
        try {
            bridge.start()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                bridge.observeEvents().filter { it is CognitiveRequestStarted ||
                    it is CognitiveReasoningStarted || it is InferenceObserved }
                    .take(3).toList(observed)
            }
            val sensitive = "PRIVATE-DEVICE-NOTE-DO-NOT-LOG"
            val result = LinuxCognitiveTurn(provider, storage, bridge).ask(sensitive)
            collector.join()
            assertTrue(observed[0] is CognitiveRequestStarted)
            assertTrue(observed[1] is CognitiveReasoningStarted)
            val evidence = observed[2] as InferenceObserved
            assertTrue(evidence.success)
            assertEquals(result.interactionId, evidence.requestId)
            assertFalse(observed.joinToString().contains(sensitive))
        } finally {
            provider.close()
            bridge.stop()
            storage.close()
        }
    }

}
