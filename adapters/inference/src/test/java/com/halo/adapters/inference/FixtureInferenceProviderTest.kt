package com.halo.adapters.inference

import com.halo.sdk.provider.AiCapability
import com.halo.sdk.provider.AiContext
import com.halo.sdk.provider.AiModel
import com.halo.sdk.provider.AiProviderException
import com.halo.sdk.provider.AiRequest
import com.halo.sdk.provider.AiResponse
import com.halo.sdk.provider.AiStreamEvent
import com.halo.sdk.provider.AiExecutionLocation
import com.halo.sdk.provider.ProviderAuthInfo
import com.halo.sdk.provider.ProviderAuthKind
import com.halo.sdk.provider.ProviderAuthState
import com.halo.sdk.provider.ProviderStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FixtureInferenceProviderTest {

    @Test
    fun `fixture provider generates configured response`() = runTest {
        val provider = FixtureInferenceProvider(FixtureProviderConfig(
            id = "test-fixture",
            displayName = "Test Fixture",
            defaultResponse = "Hello from fixture!",
            responses = mapOf("test-model" to "Model-specific response"),
        ))

        val request = AiRequest(modelId = "test-model")
        val context = AiContext.empty()

        val response = provider.generate(request, context)

        assertEquals("Model-specific response", response.text)
        assertEquals("test-fixture", response.providerId)
        assertEquals("test-model", response.modelId)
    }

    @Test
    fun `fixture provider falls back to default response`() = runTest {
        val provider = FixtureInferenceProvider(FixtureProviderConfig(
            id = "test-fixture",
            displayName = "Test Fixture",
            defaultResponse = "Default response",
        ))

        val request = AiRequest(modelId = "unknown-model")
        val context = AiContext.empty()

        val response = provider.generate(request, context)

        assertEquals("Default response", response.text)
    }

    @Test
    fun `fixture provider streams response`() = runTest {
        val provider = FixtureInferenceProvider(FixtureProviderConfig(
            id = "test-fixture",
            displayName = "Test Fixture",
            defaultResponse = "Streaming response",
        ))

        val request = AiRequest(modelId = "default")
        val context = AiContext.empty()

        val events = provider.stream(request, context).toList()

        assertEquals(2, events.size)
        assertTrue(events[0] is AiStreamEvent.TextDelta)
        assertEquals("Streaming response", (events[0] as AiStreamEvent.TextDelta).text)
        assertTrue(events[1] is AiStreamEvent.Done)
    }

    @Test
    fun `fixture provider lists configured models`() = runTest {
        val models = listOf(
            AiModel(
                id = "model-1",
                displayName = "Model 1",
                providerId = "test-fixture",
                inputCapabilities = setOf(AiCapability.TEXT),
                contextLength = 4096,
                supportsStreaming = true,
                supportsTools = false,
            ),
            AiModel(
                id = "model-2",
                displayName = "Model 2",
                providerId = "test-fixture",
                inputCapabilities = setOf(AiCapability.TEXT, AiCapability.IMAGE),
                contextLength = 8192,
                supportsStreaming = true,
                supportsTools = true,
            ),
        )

        val provider = FixtureInferenceProvider(FixtureProviderConfig(
            id = "test-fixture",
            displayName = "Test Fixture",
            models = models,
        ))

        val listedModels = provider.listModels()

        assertEquals(2, listedModels.size)
        assertEquals("model-1", listedModels[0].id)
        assertEquals("model-2", listedModels[1].id)
        assertEquals(AiExecutionLocation.LOCAL, listedModels[0].executionLocation)
    }

    @Test
    fun `fixture provider returns ready status`() = runTest {
        val provider = FixtureInferenceProvider(FixtureProviderConfig(
            id = "test-fixture",
            displayName = "Test Fixture",
        ))

        val status = provider.getStatus()

        assertEquals(ProviderStatus.State.READY, status.state)
    }

    @Test
    fun `fixture provider test connection succeeds`() = runTest {
        val provider = FixtureInferenceProvider(FixtureProviderConfig(
            id = "test-fixture",
            displayName = "Test Fixture",
        ))

        val connected = provider.testConnection()

        assertTrue(connected)
    }

    @Test
    fun `fixture provider reports capabilities`() = runTest {
        val provider = FixtureInferenceProvider(FixtureProviderConfig(
            id = "test-fixture",
            displayName = "Test Fixture",
            capabilities = setOf(AiCapability.TEXT, AiCapability.STREAMING, AiCapability.TOOLS),
        ))

        val capabilities = provider.getCapabilities()

        assertTrue(AiCapability.TEXT in capabilities)
        assertTrue(AiCapability.STREAMING in capabilities)
        assertTrue(AiCapability.TOOLS in capabilities)
    }
}