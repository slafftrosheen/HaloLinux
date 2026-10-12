package com.halo.host.linux

import com.halo.adapters.inference.FixtureInferenceProvider
import com.halo.sdk.provider.AiContext
import com.halo.sdk.provider.AiInferenceProvider
import com.halo.sdk.provider.AiRequest
import com.halo.sdk.provider.AiResponse
import com.halo.sdk.provider.ToolCall
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LinuxProviderLifecycleTest {
    private fun fixture(): AiInferenceProvider = FixtureInferenceProvider()

    @Test fun disabledProviderNeverInventsAnAnswer() = runBlocking {
        val lifecycle = LinuxProviderLifecycle(null, null)
        assertEquals(LinuxProviderPhase.DISABLED, lifecycle.snapshot().phase)
        assertFalse(lifecycle.checkConnectivity())
        assertThrows(Exception::class.java) {
            runBlocking { lifecycle.generate("Hello", "test-interaction") }
        }
    }

    @Test fun explicitlyPinnedModelAndState() = runBlocking {
        val lifecycle = LinuxProviderLifecycle(fixture(), "fixture-model")
        assertEquals(LinuxProviderPhase.CONFIGURED, lifecycle.snapshot().phase)
        val response = lifecycle.generate("Hello", "interaction-1")
        assertEquals("fixture-model", response.modelId)
        assertFalse(lifecycle.snapshot().inFlight)
        assertEquals(1L, lifecycle.snapshot().attempts)
        assertEquals(1L, lifecycle.snapshot().successes)
        assertEquals(LinuxProviderPhase.AVAILABLE, lifecycle.snapshot().phase)
        assertNull(lifecycle.snapshot().lastFailureType)
        lifecycle.close()
        assertEquals(LinuxProviderPhase.STOPPED, lifecycle.snapshot().phase)
    }

    @Test fun toolCallsAreRejectedAndNotExecuted() = runBlocking {
        val toolProducingProvider = object : AiInferenceProvider by fixture() {
            override suspend fun generate(request: AiRequest, context: AiContext): AiResponse =
                AiResponse(
                    text = "",
                    providerId = "fixture",
                    modelId = request.modelId,
                    toolCalls = listOf(ToolCall("tool-1", "run-shell", emptyMap()))
                )
        }
        val lifecycle = LinuxProviderLifecycle(toolProducingProvider, "fixture-model")
        assertThrows(Exception::class.java) {
            runBlocking { lifecycle.generate("Execute a shell command", "interaction-2") }
        }
        assertEquals(0L, lifecycle.snapshot().successes)
        assertEquals("UNSUPPORTED_CAPABILITY", lifecycle.snapshot().lastFailureType)
        assertEquals(LinuxProviderPhase.DEGRADED, lifecycle.snapshot().phase)
        lifecycle.close()
    }

    @Test fun explicitConnectivityProbe() = runBlocking {
        val lifecycle = LinuxProviderLifecycle(fixture(), "fixture-model")
        assertTrue(lifecycle.checkConnectivity())
        assertEquals(LinuxProviderPhase.AVAILABLE, lifecycle.snapshot().phase)
        assertEquals(0L, lifecycle.snapshot().attempts)
        lifecycle.close()
    }
    @Test fun shutdownDuringInferenceNeverRestoresAvailableState() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocking = object : AiInferenceProvider by fixture() {
            override suspend fun generate(request: AiRequest, context: AiContext): AiResponse {
                entered.complete(Unit)
                release.await()
                return AiResponse(text = "late answer", providerId = "fixture", modelId = request.modelId)
            }
        }
        val lifecycle = LinuxProviderLifecycle(blocking, "fixture-model", timeoutMs = 3000)
        val job = async(Dispatchers.Default) {
            runCatching { lifecycle.generate("Wait until shutdown", "inflight") }
        }
        withTimeout(1000) { entered.await() }
        assertTrue(lifecycle.snapshot().inFlight)
        lifecycle.close()
        release.complete(Unit)
        val outcome = withTimeout(1000) { job.await() }
        assertTrue(outcome.isFailure)
        assertEquals(LinuxProviderPhase.STOPPED, lifecycle.snapshot().phase)
        assertEquals(0L, lifecycle.snapshot().successes)
        assertFalse(lifecycle.snapshot().inFlight)
        lifecycle.close() // idempotent
    }

    @Test fun secondRequestFailsBusyWithoutSendingSecondModelCall() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val blocking = object : AiInferenceProvider by fixture() {
            override suspend fun generate(request: AiRequest, context: AiContext): AiResponse {
                calls.incrementAndGet()
                entered.complete(Unit)
                release.await()
                return AiResponse(text = "finished", providerId = "fixture",
                    modelId = request.modelId)
            }
        }
        val lifecycle = LinuxProviderLifecycle(
            blocking, "fixture-model", timeoutMs = 3_000, admissionTimeoutMs = 40
        )
        val first = async(Dispatchers.Default) {
            runCatching { lifecycle.generate("first", "first-turn") }
        }
        try {
            withTimeout(1000) { entered.await() }
            val busy = runCatching { lifecycle.generate("second", "second-turn") }
            assertTrue(busy.isFailure)
            assertEquals(com.halo.sdk.provider.AiErrorType.RATE_LIMITED,
                (busy.exceptionOrNull() as com.halo.sdk.provider.AiProviderException).type)
            assertEquals(1, calls.get())
            assertTrue(lifecycle.snapshot().inFlight)
            assertEquals(LinuxProviderPhase.INFERENCING, lifecycle.snapshot().phase)
        } finally {
            release.complete(Unit)
            withTimeout(1000) { first.await() }
            lifecycle.close()
        }
    }

    @Test fun healthCheckDoesNotDegradeAnActiveGeneration() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val provider = object : AiInferenceProvider by fixture() {
            override suspend fun generate(request: AiRequest, context: AiContext): AiResponse {
                entered.complete(Unit)
                release.await()
                return AiResponse(text = "complete", providerId = "fixture",
                    modelId = request.modelId)
            }
        }
        val lifecycle = LinuxProviderLifecycle(
            provider, "fixture-model", timeoutMs = 5_000, admissionTimeoutMs = 40
        )
        val running = async(Dispatchers.Default) {
            lifecycle.generate("first", "interaction-1")
        }
        try {
            withTimeout(1_000) { entered.await() }
            assertFalse(lifecycle.checkConnectivity())
            assertEquals(LinuxProviderPhase.INFERENCING, lifecycle.snapshot().phase)
            assertTrue(lifecycle.snapshot().inFlight)
            assertNull(lifecycle.snapshot().lastFailureType)
        } finally {
            release.complete(Unit)
            withTimeout(1_000) { running.await() }
            lifecycle.close()
        }
    }

}
