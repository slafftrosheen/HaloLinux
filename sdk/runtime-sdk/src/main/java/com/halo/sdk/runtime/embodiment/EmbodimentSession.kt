package com.halo.sdk.runtime.embodiment

import com.halo.sdk.runtime.DeviceIdentity
import kotlinx.coroutines.flow.StateFlow

/**
 * A semantic session over a transport layer.
 * 
 * Unlike a raw transport, a Session understands the handshake, the `READY` state,
 * and how to serialize/deserialize semantic intents and execution results.
 */
interface EmbodimentSession {
    /**
     * The canonical identity of the device this session belongs to.
     */
    val identity: DeviceIdentity

    /**
     * The lifecycle state of the session (e.g. CONNECTING, READY, ERROR).
     * The session only transitions to READY after a successful handshake.
     */
    val state: StateFlow<SessionState>

    /**
     * Attempt to establish the session.
     */
    suspend fun connect()

    /**
     * Cleanly close the session.
     */
    suspend fun disconnect()

    /**
     * Dispatch an intent to the device through this session.
     * This will fail if the session is not in the READY state.
     */
    suspend fun dispatch(intent: EmbodimentIntent)
    
    /**
     * Terminate the session completely and clean up any resources (scopes, listeners).
     */
    suspend fun dispose()
}
