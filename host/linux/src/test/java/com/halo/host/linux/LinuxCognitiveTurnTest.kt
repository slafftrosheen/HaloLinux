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

    @Test fun textTurnIsDurableAndContentIsNotNeededForStatus() = runBlocking<Unit> {
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

    @Test fun unconfiguredInferenceDoesNotPersistFabricatedConversation() = runBlocking<Unit> {
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
    @Test fun canonicalCognitiveEventsAreContentFree() = runBlocking<Unit> {
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

    @Test fun mainDbCompletionSurvivesSecondaryJournalFailureWithoutDuplicateRetry() = runBlocking<Unit> {
        val storage = LinuxStorage()
        storage.initialize(LinuxStorageConfig(
            dataDir=folder.resolve("consistent-data").toString(),
            configDir=folder.resolve("consistent-config").toString(),
            cacheDir=folder.resolve("consistent-cache").toString()
        ))
        val runtime = LinuxRuntimeBridge(storage)
        val provider = LinuxProviderLifecycle(FixtureInferenceProvider(), "fixture-model")
        val fake = object : LinuxRecoverySupport {
            override suspend fun inspect(): org.json.JSONObject = org.json.JSONObject()
            override suspend fun beginTextTurn(
                interactionId: String, text: String, providerId: String?, modelId: String?,
            ): String = "canonical-1"
            override suspend fun recordTextResponse(runId: String?, response: String) = Unit
            override suspend fun commitTextDelivery(runId: String?, conversationId: String) {
                throw IllegalStateException("Simulated secondary database failure")
            }
            override fun close() = Unit
        }
        try {
            runtime.start()
            val answer = LinuxCognitiveTurn(provider, storage, runtime,
                recoverySupport = { fake }).ask("A private test question")
            assertEquals("NEEDS_RECONCILIATION", answer.canonicalJournalState)
            assertEquals("canonical-1", answer.agentRunId)
            assertEquals(answer.text,
                storage.loadConversation(answer.conversationId)?.messages?.last()?.content)
            assertEquals(1L, storage.checkDatabase().conversationCount)
        } finally {
            provider.close()
            runtime.stop()
            storage.close()
        }
    }

    @Test fun oversizedJsonAnswerCannotBecomeDeliveredConversation() = runBlocking<Unit> {
        val store = LinuxStorage()
        store.initialize(LinuxStorageConfig(
            dataDir=folder.resolve("limit-data").toString(),
            configDir=folder.resolve("limit-config").toString(),
            cacheDir=folder.resolve("limit-cache").toString()
        ))
        val runtime = LinuxRuntimeBridge(store)
        val model = object : com.halo.sdk.provider.AiInferenceProvider by FixtureInferenceProvider() {
            override suspend fun generate(
                request: com.halo.sdk.provider.AiRequest,
                context: com.halo.sdk.provider.AiContext,
            ) = com.halo.sdk.provider.AiResponse(
                text = "large-answer-".repeat(12_000),
                providerId = "fixture", modelId = request.modelId
            )
        }
        val provider = LinuxProviderLifecycle(model, "fixture-model")
        try {
            runtime.start()
            val failure = assertThrows(com.halo.sdk.provider.AiProviderException::class.java) {
                runBlocking { LinuxCognitiveTurn(provider, store, runtime).ask("small request") }
            }
            assertEquals(com.halo.sdk.provider.AiErrorType.INVALID_RESPONSE, failure.type)
            assertEquals(0L, store.checkDatabase().conversationCount)
        } finally {
            provider.close()
            runtime.stop()
            store.close()
        }
    }

}
