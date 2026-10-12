package com.halo.sdk.runtime.embodiment

/**
 * Transport-independent boundary for sending intents to an embodiment.
 * 
 * The gateway is responsible for transporting the intent to the physical
 * or simulated robot and publishing correlated ExecutionResult updates 
 * to the EventBus.
 */
interface EmbodimentGateway {
    /**
     * Dispatch an intent to the embodiment. This is a non-blocking request;
     * execution lifecycle events will be published asynchronously.
     */
    suspend fun dispatch(intent: EmbodimentIntent)
}
