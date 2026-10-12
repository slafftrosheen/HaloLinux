package com.halo.sdk.runtime.embodiment

import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.halo.sdk.runtime.embodiment.ExecutionResult
import com.halo.sdk.runtime.embodiment.ExecutionStatus
import com.halo.sdk.runtime.EventBus
import com.halo.sdk.runtime.EmbodimentExecutionEvent

import com.halo.sdk.runtime.DeviceIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

import com.halo.sdk.runtime.ExecutionMode

import kotlin.coroutines.CoroutineContext

/**
 * A semantic session for BuddyBot. Wraps a raw transport (like WebSocket)
 * and handles the v1 protocol handshake, JSON translation, and intent tracking.
 */
class BuddyBotSession(
    override val identity: DeviceIdentity,
    private val transport: EmbodimentTransport,
    private val eventBus: EventBus,
    private val executionMode: ExecutionMode = ExecutionMode.LIVE,
    private val coroutineContext: CoroutineContext = Dispatchers.Default
) : EmbodimentSession {

    private val _state = MutableStateFlow(SessionState.SAVED)
    override val state: StateFlow<SessionState> = _state.asStateFlow()
    private var requestIdCounter = 1

    private val sessionScope = CoroutineScope(SupervisorJob() + coroutineContext)
    private val pendingIntents = ConcurrentHashMap<String, Long>() // intentId -> timestamp

    init {
        sessionScope.launch {
            transport.connectionState.collect { connState ->
                when (connState) {
                    TransportConnectionState.CONNECTED -> {
                        _state.value = SessionState.CONNECTED
                        sendHandshake()
                    }
                    TransportConnectionState.DISCONNECTED, TransportConnectionState.ERROR -> {
                        val reason = if (connState == TransportConnectionState.ERROR) "TRANSPORT_ERROR" else "DEVICE_DISCONNECTED"
                        failPendingIntents(reason)
                        _state.value = if (connState == TransportConnectionState.ERROR) SessionState.ERROR else SessionState.DISCONNECTED
                    }
                    TransportConnectionState.CONNECTING -> {
                        _state.value = SessionState.CONNECTING
                    }
                }
            }
        }

        transport.onMessageReceived { message ->
            try {
                val isExecResult = message.contains("\"type\":\"exec_result\"") || message.contains("\"type\": \"exec_result\"")
                if (isExecResult) {
                    val intentIdMatch = Regex("\"intentId\"\\s*:\\s*\"([^\"]+)\"").find(message)
                    val statusMatch = Regex("\"status\"\\s*:\\s*\"([^\"]+)\"").find(message)
                    val reasonMatch = Regex("\"reason\"\\s*:\\s*\"([^\"]+)\"").find(message)

                    val intentId = intentIdMatch?.groupValues?.get(1) ?: ""
                    val statusStr = statusMatch?.groupValues?.get(1) ?: ""
                    val reason = reasonMatch?.groupValues?.get(1)

                    val status = when (statusStr) {
                        "SUCCEEDED" -> ExecutionStatus.SUCCEEDED
                        "FAILED" -> ExecutionStatus.FAILED
                        "DENIED" -> ExecutionStatus.DENIED
                        "REJECTED" -> ExecutionStatus.REJECTED
                        "ABORTED" -> ExecutionStatus.ABORTED
                        else -> ExecutionStatus.FAILED
                    }

                    val finalReason = if (statusStr != "SUCCEEDED" && statusStr != "FAILED" && statusStr != "DENIED" && statusStr != "REJECTED" && statusStr != "ABORTED") {
                        "UNKNOWN_EXECUTION_STATUS:$statusStr"
                    } else {
                        reason
                    }

                    if (intentId.isNotEmpty()) {
                        pendingIntents.remove(intentId)
                        sessionScope.launch {
                            eventBus.publish(EmbodimentExecutionEvent(
                                device = identity,
                                result = ExecutionResult(
                                    intentId = intentId,
                                    status = status,
                                    reason = finalReason,
                                    timestamp = System.currentTimeMillis(),
                                    embodimentId = identity.deviceId
                                )
                            ))
                        }
                    }
                } else if (message.contains("\"type\":\"handshake_ack\"") || message.contains("\"type\": \"handshake_ack\"") ||
                    (message.contains("\"type\":\"ack\"") && message.contains("\"ok\":true"))) {
                    _state.value = SessionState.READY
                } else if (message.contains("\"type\":\"paired\"") || message.contains("\"type\": \"paired\"")) {
                    val tokenMatch = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(message)
                    tokenMatch?.groupValues?.get(1)?.let { token ->
                        sessionToken = token
                    }
                }
            } catch (e: Exception) {
                // Ignore parse errors
            }
        }
    }

    var sessionToken: String? = null
        private set

    val hasToken: Boolean get() = !sessionToken.isNullOrEmpty()

    suspend fun pair(code: String) {
        val pairJson = "{\"v\":1,\"id\":${requestIdCounter++},\"type\":\"pair\",\"code\":\"$code\"}"
        sessionScope.launch {
            transport.send(pairJson)
        }
    }

    private fun sendHandshake() {
        val handshakeJson = "{\"v\":1,\"id\":${requestIdCounter++},\"type\":\"handshake\"}"
        sessionScope.launch {
            transport.send(handshakeJson)
        }
    }

    private suspend fun failPendingIntents(reason: String) {
        val keys = pendingIntents.keys().toList()
        for (intentId in keys) {
            pendingIntents.remove(intentId)
            eventBus.publish(EmbodimentExecutionEvent(
                device = identity,
                result = ExecutionResult(
                    intentId = intentId,
                    status = ExecutionStatus.FAILED,
                    reason = reason,
                    timestamp = System.currentTimeMillis(),
                    embodimentId = identity.deviceId
                )
            ))
        }
    }

    override suspend fun connect() {
        if (_state.value == SessionState.CONNECTING || _state.value == SessionState.READY) return
        _state.value = SessionState.CONNECTING
        if (executionMode == ExecutionMode.REPLAY) {
            _state.value = SessionState.CONNECTED
            _state.value = SessionState.READY
            return
        }
        transport.connect()
    }

    override suspend fun disconnect() {
        _state.value = SessionState.DISCONNECTING
        if (executionMode != ExecutionMode.REPLAY) {
            transport.disconnect()
        }
        _state.value = SessionState.DISCONNECTED
    }

    override suspend fun dispatch(intent: EmbodimentIntent) {
        if (_state.value != SessionState.READY && executionMode != ExecutionMode.LIVE) {
            // Replay mode or non-live check
        } else if (_state.value != SessionState.READY) {
            return
        }

        if (executionMode == ExecutionMode.REPLAY) {
            return
        }

        if (pendingIntents.containsKey(intent.intentId)) {
            return // Duplicate intent prevention
        }

        val map = mutableMapOf<String, Any>()
        map["v"] = 1
        map["id"] = requestIdCounter++
        map["intentId"] = intent.intentId
        sessionToken?.let { map["token"] = it }

        when (intent.kind) {
            IntentKind.MOVE -> {
                map["type"] = "move"
                val mode = when (intent.driveMode) {
                    DriveMode.FORWARD -> "forward"
                    DriveMode.REVERSE -> "reverse"
                    DriveMode.ROTATE_LEFT -> "turn_left"
                    DriveMode.ROTATE_RIGHT -> "turn_right"
                    else -> "stop"
                }
                map["mode"] = mode
                // BuddyBot Wi-Fi protocol accepts bounded drive pulses of 50–250 ms.
                // Keep the transport authoritative so every UI/engine caller is safe.
                map["durationMs"] = if (intent.durationMs > 0) {
                    intent.durationMs.coerceIn(50, 250)
                } else {
                    50
                }
            }
            IntentKind.STOP -> {
                map["type"] = "stop"
            }
            IntentKind.ARM -> {
                map["type"] = "arm"
            }
            IntentKind.DISARM -> {
                map["type"] = "disarm"
            }
            IntentKind.ACTION -> {
                map["type"] = "action"
                val actionName = when (intent.action) {
                    ActionId.NOD_YES -> "wave"
                    ActionId.SHAKE_NO -> "wave"
                    ActionId.CELEBRATE -> "celebrate"
                    ActionId.LOOK_LEFT -> "look_left"
                    ActionId.LOOK_RIGHT -> "look_right"
                    ActionId.SHIVER -> "shiver"
                    else -> "none"
                }
                map["action"] = actionName
            }
            IntentKind.SET_MOOD -> {
                map["type"] = "mood"
                val moodName = when (intent.mood) {
                    Mood.HAPPY -> "happy"
                    Mood.CURIOUS -> "curious"
                    Mood.SLEEPY -> "sleepy"
                    Mood.SURPRISED -> "excited"
                    Mood.ANGRY -> "alert"
                    Mood.SCARED -> "alert"
                    Mood.IDLE -> "idle"
                    else -> "idle"
                }
                map["mood"] = moodName
            }
            IntentKind.NEXT_PERSONA -> {
                map["type"] = "persona_next"
            }
            IntentKind.ACCESSORY -> {
                map["type"] = "accessory"
                map["index"] = intent.accessoryIndex
                map["active"] = intent.accessoryActive
            }
            else -> return
        }

        val jsonStr = map.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
            if (v is String) "\"$k\":\"$v\"" else "\"$k\":$v"
        }
        pendingIntents[intent.intentId] = System.currentTimeMillis()
        transport.send(jsonStr)
    }

    override suspend fun dispose() {
        disconnect()
        sessionScope.cancel()
    }
}
