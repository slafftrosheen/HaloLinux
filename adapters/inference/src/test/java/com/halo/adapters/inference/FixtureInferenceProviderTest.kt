package com.halo.adapters.inference

import com.halo.sdk.provider.AiContext
import com.halo.sdk.provider.AiRequest
import com.halo.sdk.provider.AiStreamEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FixtureInferenceProviderTest {
    @Test fun configuredResponse() = runTest {
        val provider = FixtureInferenceProvider(FixtureProviderConfig(
            responses = mapOf("fixture-model" to "Working fixture")
        ))
        val answer = provider.generate(AiRequest(text="Hello", modelId="fixture-model"), AiContext())
        assertEquals("Working fixture", answer.text)
        assertEquals("fixture", answer.providerId)
    }

    @Test fun structuredStreamingCompletes() = runTest {
        val provider = FixtureInferenceProvider(FixtureProviderConfig(defaultResponse="Hi"))
        val events = provider.stream(AiRequest(text="Hello", modelId="fixture-model"), AiContext()).toList()
        assertTrue(events.first() is AiStreamEvent.Started)
        assertEquals("Hi", (events[1] as AiStreamEvent.Token).text)
        assertEquals("Hi", (events.last() as AiStreamEvent.Completed).response.text)
    }

    @Test fun modelDiscoveryAndConnection() = runTest {
        val provider = FixtureInferenceProvider()
        assertTrue(provider.testConnection())
        assertEquals("fixture-model", provider.listModels().single().id)
    }
}
