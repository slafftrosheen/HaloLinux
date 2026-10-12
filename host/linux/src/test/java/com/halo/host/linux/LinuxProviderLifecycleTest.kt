package com.halo.host.linux

import com.halo.adapters.inference.FixtureInferenceProvider
import com.halo.sdk.provider.AiContext
import com.halo.sdk.provider.AiInferenceProvider
import com.halo.sdk.provider.AiRequest
import com.halo.sdk.provider.AiResponse
import com.halo.sdk.provider.ToolCall
import kotlinx.coroutines.runBlocking
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
}
