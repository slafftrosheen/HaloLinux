package com.halo.sdk.runtime.embodiment

import kotlinx.coroutines.flow.StateFlow

enum class TransportConnectionState {
    CONNECTING, CONNECTED, DISCONNECTED, ERROR
}

/**
 * Represents a raw physical or network transport (e.g., BLE, WebSocket, Serial).
 * A transport does NOT understand semantic intents or JSON structures. It only
 * knows about bytes (or strings) and connection lifecycle.
 */
interface EmbodimentTransport {
    
    /**
     * The current state of the transport connection. 
     * Note: A transport can only be CONNECTED, CONNECTING, DISCONNECTED, ERROR.
     * It does not know about READY.
     */
    val connectionState: StateFlow<TransportConnectionState>

    /**
     * Attempt to establish the physical connection.
     */
    suspend fun connect()

    /**
     * Tear down the physical connection.
     */
    suspend fun disconnect()

    /**
     * Send a raw string message over the transport.
     */
    suspend fun send(message: String): Boolean

    /**
     * Send a raw byte message over the transport.
     */
    suspend fun send(bytes: ByteArray): Boolean

    /**
     * Set a callback to receive incoming string messages from the device.
     */
    fun onMessageReceived(handler: (String) -> Unit)

    /**
     * Set a callback to receive incoming byte messages from the device.
     */
    fun onBytesReceived(handler: (ByteArray) -> Unit)
}
