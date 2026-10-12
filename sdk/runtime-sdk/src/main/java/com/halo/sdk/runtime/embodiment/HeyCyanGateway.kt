package com.halo.sdk.runtime.embodiment

import com.halo.sdk.runtime.*

class HeyCyanGateway(
    private val eventBus: EventBus,
    private val sessionManager: DeviceSessionManager,
    private val executionMode: ExecutionMode = ExecutionMode.LIVE
) : EmbodimentGateway {
    
    override suspend fun dispatch(intent: EmbodimentIntent) {
        if (executionMode == ExecutionMode.REPLAY) {
            // Execution event will be injected by the replay player
            return
        }

        val activeSession = sessionManager.activeSessions.value.find { 
            it.identity.deviceId == intent.targetDeviceId 
        }

        if (activeSession != null && activeSession.state.value == SessionState.READY) {
            activeSession.dispatch(intent)
        } else {
            val status = if (activeSession == null) ExecutionStatus.REJECTED else ExecutionStatus.FAILED
            val reason = if (activeSession == null) "Device unknown or offline" else "Device not ready"
            val targetId = intent.targetDeviceId ?: "unknown"
            
            val knownDevice = sessionManager.registry.getDevice(targetId)
            val deviceIdentity = activeSession?.identity ?: knownDevice?.identity ?: DeviceIdentity(targetId, "Unknown", "Unknown")
            
            eventBus.publish(EmbodimentExecutionEvent(
                device = deviceIdentity,
                result = ExecutionResult(
                    intentId = intent.intentId,
                    status = status,
                    reason = reason,
                    timestamp = System.currentTimeMillis(),
                    embodimentId = targetId
                )
            ))
        }
    }
}
