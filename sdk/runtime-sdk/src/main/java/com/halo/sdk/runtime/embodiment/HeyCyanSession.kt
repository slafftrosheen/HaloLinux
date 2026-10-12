package com.halo.sdk.runtime.embodiment

import com.halo.sdk.runtime.DeviceIdentity
import com.halo.sdk.runtime.EventBus
import com.halo.sdk.runtime.ObservationUpdated
import com.halo.sdk.runtime.EmbodimentExecutionEvent
import com.halo.sdk.runtime.embodiment.ExecutionResult as EmbodimentExecutionResult
import com.halo.sdk.runtime.embodiment.ExecutionStatus as EmbodimentExecutionStatus
import com.halo.sdk.runtime.embodiment.heycyan.HeyCyanController
import com.halo.sdk.runtime.embodiment.heycyan.HeyCyanOperation
import com.halo.sdk.runtime.embodiment.heycyan.HeyCyanExecutionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.CoroutineContext

/**
 * A semantic session for HeyCyan. Wraps a raw transport.
 */
class HeyCyanSession(
    override val identity: DeviceIdentity,
    private val controller: HeyCyanController,
    private val transport: EmbodimentTransport,
    private val eventBus: EventBus,
    private val coroutineContext: CoroutineContext = Dispatchers.Default
) : EmbodimentSession {

    private val _state = MutableStateFlow(SessionState.SAVED)
    override val state: StateFlow<SessionState> = _state.asStateFlow()
    private val sessionScope = CoroutineScope(SupervisorJob() + coroutineContext)
    private val pendingIntents = ConcurrentHashMap<String, Long>()
    private val dispatchJobs = ConcurrentHashMap<String, Job>()

    init {
        sessionScope.launch {
            transport.connectionState.collectLatest { connState ->
                when (connState) {
                    TransportConnectionState.CONNECTED -> handleConnected()
                    TransportConnectionState.DISCONNECTED, TransportConnectionState.ERROR -> {
                        failPendingIntents(if (connState == TransportConnectionState.ERROR) "TRANSPORT_ERROR" else "DEVICE_DISCONNECTED")
                        _state.value = if (connState == TransportConnectionState.ERROR) SessionState.ERROR else SessionState.DISCONNECTED
                    }
                    TransportConnectionState.CONNECTING -> _state.value = SessionState.CONNECTING
                }
            }
        }
    }

    private suspend fun handleConnected() {
        _state.value = SessionState.CONNECTED
        _state.value = SessionState.HANDSHAKE
        delay(100)
        _state.value = SessionState.CAPABILITY_SYNC
        _state.value = SessionState.STATE_SYNC
        _state.value = if (syncInitialState()) SessionState.READY else SessionState.ERROR
    }

    private suspend fun syncInitialState(): Boolean = withTimeoutOrNull(10000L) {
        runCatching { controller.execute(HeyCyanOperation.GetBattery) }
        delay(300L)
        runCatching { controller.execute(HeyCyanOperation.GetDeviceInfo) }
        delay(300L)
        runCatching { controller.execute(HeyCyanOperation.GetMediaInventory) }
        delay(300L)
        true
    } ?: false

    private suspend fun failPendingIntents(reason: String) {
        val keys = pendingIntents.keys().toList()
        for (intentId in keys) {
            if (!pendingIntents.remove(intentId, pendingIntents[intentId])) continue
            dispatchJobs.remove(intentId)?.cancel()
            reportResult(intentId, EmbodimentExecutionStatus.FAILED, reason)
        }
    }

    override suspend fun connect() {
        when (_state.value) {
            SessionState.CONNECTING,
            SessionState.CONNECTED,
            SessionState.HANDSHAKE,
            SessionState.CAPABILITY_SYNC,
            SessionState.STATE_SYNC,
            SessionState.READY -> return
            else -> Unit
        }

        _state.value = SessionState.CONNECTING
        try { transport.connect() } catch (e: CancellationException) {
            _state.value = SessionState.DISCONNECTED
            throw e
        }
    }

    override suspend fun disconnect() {
        _state.value = SessionState.DISCONNECTING
        try { transport.disconnect() } finally { _state.value = SessionState.DISCONNECTED }
    }

    override suspend fun dispatch(intent: EmbodimentIntent) {
        if (_state.value != SessionState.READY) {
            reportResult(intent.intentId, EmbodimentExecutionStatus.REJECTED, "Device not ready (state: ${_state.value})")
            return
        }
        val operation = mapIntentToOperation(intent) ?: run {
            reportResult(intent.intentId, EmbodimentExecutionStatus.REJECTED, "UNSUPPORTED_INTENT: ${intent.kind} is not supported by this device")
            return
        }
        if (pendingIntents.putIfAbsent(intent.intentId, System.currentTimeMillis()) != null) return

        lateinit var job: Job
        job = sessionScope.launch {
            val completion = async { waitForCompletionSignal(operation) }
            try {
                controller.execute(operation).fold(
                    onSuccess = { executionResult ->
                        when (executionResult) {
                            is HeyCyanExecutionResult.Acknowledged -> {
                                reportResult(intent.intentId, EmbodimentExecutionStatus.DISPATCHED)
                                val completed = completion.await()
                                if (completed) reportResult(intent.intentId, EmbodimentExecutionStatus.SUCCEEDED)
                                else reportResult(intent.intentId, EmbodimentExecutionStatus.UNCONFIRMED, "Execution dispatched but confirmation timed out")
                            }
                            is HeyCyanExecutionResult.Completed -> {
                                completion.cancel()
                                reportResult(intent.intentId, EmbodimentExecutionStatus.SUCCEEDED, metadata = executionResult.metadata)
                            }
                        }
                    },
                    onFailure = { error ->
                        completion.cancel()
                        reportResult(intent.intentId, EmbodimentExecutionStatus.FAILED, error.message ?: "Unknown execution error")
                    }
                )
            } catch (e: CancellationException) {
                completion.cancel()
                throw e
            } catch (e: Exception) {
                completion.cancel()
                reportResult(intent.intentId, EmbodimentExecutionStatus.FAILED, "Execution failed: ${e.message}")
            } finally {
                pendingIntents.remove(intent.intentId)
                dispatchJobs.remove(intent.intentId, job)
            }
        }
        dispatchJobs[intent.intentId] = job
        if (job.isCompleted) dispatchJobs.remove(intent.intentId, job)
    }

    private suspend fun waitForCompletionSignal(operation: HeyCyanOperation): Boolean {
        return withTimeoutOrNull(30000L) {
            fun ObservationUpdated.belongsToThisDevice(): Boolean = observation.sourceDeviceId == identity.deviceId
            fun ObservationUpdated.isPhoto(): Boolean {
                val mode = observation.metadata["mode"]?.toString()
                return mode == "1" || mode == "PHOTO" || mode == "0x01"
            }
            fun ObservationUpdated.isVideo(): Boolean {
                val mode = observation.metadata["mode"]?.toString()
                return mode == "2" || mode == "VIDEO" || mode == "0x02"
            }
            fun ObservationUpdated.isAudio(): Boolean {
                val mode = observation.metadata["mode"]?.toString()
                return mode == "4" || mode == "AUDIO" || mode == "0x04"
            }
            fun ObservationUpdated.isEvent(name: String): Boolean =
                belongsToThisDevice() && observation.metadata["event"] == name

            when (operation) {
                HeyCyanOperation.TakePhoto -> {
                    eventBus.observe(ObservationUpdated::class.java).first {
                        it.isEvent("MODE_CHANGED") && it.isPhoto()
                    }
                    true
                }
                HeyCyanOperation.StartVideo -> {
                    eventBus.observe(ObservationUpdated::class.java).first {
                        it.isEvent("MODE_CHANGED") && it.isVideo()
                    }
                    true
                }
                HeyCyanOperation.StopVideo -> {
                    eventBus.observe(ObservationUpdated::class.java).first {
                        it.isEvent("MODE_CHANGED") && !it.isVideo()
                    }
                    true
                }
                HeyCyanOperation.StartRecording -> {
                    eventBus.observe(ObservationUpdated::class.java).first {
                        it.isEvent("MODE_CHANGED") && it.isAudio()
                    }
                    true
                }
                HeyCyanOperation.StopRecording -> {
                    eventBus.observe(ObservationUpdated::class.java).first {
                        it.isEvent("MODE_CHANGED") && !it.isAudio()
                    }
                    true
                }
                HeyCyanOperation.GetMediaInventory -> {
                    eventBus.observe(ObservationUpdated::class.java).first {
                        it.isEvent("MEDIA_COUNTS_UPDATED")
                    }
                    true
                }
                else -> false
            }
        } ?: false
    }

    private fun mapIntentToOperation(intent: EmbodimentIntent): HeyCyanOperation? = when (intent.kind) {
        IntentKind.CAPTURE_PHOTO -> HeyCyanOperation.TakePhoto
        IntentKind.START_VIDEO -> HeyCyanOperation.StartVideo
        IntentKind.STOP_VIDEO -> HeyCyanOperation.StopVideo
        IntentKind.START_AUDIO_RECORDING -> HeyCyanOperation.StartRecording
        IntentKind.STOP_AUDIO_RECORDING -> HeyCyanOperation.StopRecording
        IntentKind.FETCH_MEDIA_COUNT -> HeyCyanOperation.GetMediaInventory
        else -> null
    }

    private suspend fun reportResult(intentId: String, status: EmbodimentExecutionStatus, reason: String? = null, metadata: Map<String, String> = emptyMap()) {
        eventBus.publish(EmbodimentExecutionEvent(
            device = identity,
            result = EmbodimentExecutionResult(
                intentId = intentId,
                status = status,
                reason = reason,
                timestamp = System.currentTimeMillis(),
                embodimentId = identity.deviceId,
                metadata = metadata
            )
        ))
    }

    override suspend fun dispose() {
        disconnect()
        failPendingIntents("SESSION_DISPOSED")
        sessionScope.cancel()
    }
}
