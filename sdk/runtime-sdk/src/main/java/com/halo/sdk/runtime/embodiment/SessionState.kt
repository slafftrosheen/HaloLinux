package com.halo.sdk.runtime.embodiment

/**
 * Represents the lifecycle state of a device session.
 */
enum class SessionState {
    SAVED,          // Device is known but no active attempt to connect
    DISCOVERED,     // Device is seen in proximity (e.g., BLE scan)
    CONNECTING,     // Transport is attempting to establish a connection
    CONNECTED,      // Transport connected, but semantic handshake incomplete
    HANDSHAKE,      // Authenticating and establishing session
    CAPABILITY_SYNC,// Syncing device capabilities
    STATE_SYNC,     // Syncing current state (battery, mode, etc.)
    READY,          // Handshake complete, capabilities known, ready for intents
    DISCONNECTING,  // Actively closing the session
    DISCONNECTED,   // Session cleanly closed or transport lost
    ERROR           // Session failed unrecoverably
}
