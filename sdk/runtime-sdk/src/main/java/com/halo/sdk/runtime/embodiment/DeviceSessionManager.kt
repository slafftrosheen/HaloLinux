package com.halo.sdk.runtime.embodiment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Manages active embodiment sessions, ensuring devices are connected
 * when needed and routing intents to the correct active session.
 */
class DeviceSessionManager(
    val registry: DeviceRegistry
) {
    private val _sessions = mutableMapOf<String, EmbodimentSession>()
    private val stateScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val stateJobs = mutableMapOf<String, Job>()
    private val autoConnectMutex = Mutex()

    private val _activeSessionsFlow = MutableStateFlow<List<EmbodimentSession>>(emptyList())
    val activeSessions: StateFlow<List<EmbodimentSession>> = _activeSessionsFlow.asStateFlow()

    /** Emits whenever a registered session changes state. */
    private val _stateRevision = MutableStateFlow(0L)
    val stateRevision: StateFlow<Long> = _stateRevision.asStateFlow()

    fun registerSession(session: EmbodimentSession) {
        stateJobs.remove(session.identity.deviceId)?.cancel()
        _sessions[session.identity.deviceId] = session
        stateJobs[session.identity.deviceId] = stateScope.launch {
            session.state.collect {
                _stateRevision.value += 1
                updateFlow()
            }
        }
        updateFlow()
    }

    fun unregisterSession(deviceId: String) {
        stateJobs.remove(deviceId)?.cancel()
        _sessions.remove(deviceId)
        updateFlow()
        _stateRevision.value += 1
    }

    fun getSession(deviceId: String): EmbodimentSession? = _sessions[deviceId]

    suspend fun autoConnectDevice(deviceId: String): Boolean = autoConnectMutex.withLock {
        val record = registry.getDevice(deviceId) ?: return@withLock false
        if (!record.autoConnect) return@withLock false
        val session = _sessions[deviceId] ?: return@withLock false

        if (session.state.value == SessionState.READY) return@withLock true

        // If another owner has already started this session, do not start a second
        // transport connection. Wait for that lifecycle to resolve to READY/error.
        if (isConnectionInProgress(session.state.value)) {
            return@withLock awaitReadyOrTerminal(session)
        }

        for (attempt in 1..3) {
            try {
                session.connect()
                if (awaitReadyOrTerminal(session)) {
                    return@withLock true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Continue with bounded retry.
            }

            if (attempt < 3) {
                delay(5000L * attempt)
            }
        }
        return@withLock session.state.value == SessionState.READY
    }

    suspend fun autoConnectSavedDevices() {
        for (record in registry.getSavedDevices()) {
            autoConnectDevice(record.identity.deviceId)
        }
    }

    private suspend fun awaitReadyOrTerminal(
        session: EmbodimentSession,
        timeoutMillis: Long = 30_000L
    ): Boolean {
        if (session.state.value == SessionState.READY) return true

        val resolved = withTimeoutOrNull(timeoutMillis) {
            session.state
                .filter { state ->
                    state == SessionState.READY ||
                        state == SessionState.ERROR ||
                        state == SessionState.DISCONNECTED
                }
                .first()
        }
        return resolved == SessionState.READY
    }

    private fun isConnectionInProgress(state: SessionState): Boolean = when (state) {
        SessionState.CONNECTING,
        SessionState.CONNECTED,
        SessionState.HANDSHAKE,
        SessionState.CAPABILITY_SYNC,
        SessionState.STATE_SYNC -> true
        else -> false
    }

    private fun updateFlow() {
        _activeSessionsFlow.value = _sessions.values.toList()
    }
}
