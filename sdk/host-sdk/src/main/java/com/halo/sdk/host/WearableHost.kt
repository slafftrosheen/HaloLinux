package com.halo.sdk.host

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** A physical or virtual device host exposed to the host-agnostic runtime. */
interface WearableHost {
    val id: String
    val audioSource: AudioSource
    val audioOutput: AudioOutput
    val buttons: Flow<ButtonEvent>
    val controls: Flow<AccessoryControlEvent> get() = kotlinx.coroutines.flow.emptyFlow()
    val deviceContext: StateFlow<DeviceContext>
    val connectivity: StateFlow<ConnectionState>

    suspend fun connect()
    suspend fun disconnect()
}

/** State of the audio source. */
enum class AudioSourceState {
    IDLE,
    ACTIVE,
    ERROR
}

interface AudioSource {
    val state: StateFlow<AudioSourceState>
    suspend fun start()
    suspend fun stop()

    /** Release platform resources when the owning host/runtime is disposed. */
    suspend fun close() {}

    val frames: Flow<AudioFrame>
}

interface AudioOutput {
    suspend fun speak(text: String)
}

data class AudioFrame(
    val samples: ShortArray,
    val sampleRate: Int,
    val channels: Int,
    val timestamp: Long
)

data class AudioBuffer(val frames: List<AudioFrame>, val captureId: String? = null)

enum class ButtonEvent {
    Pressed,
    Released
}

enum class DeviceConnectionState {
    DISCONNECTED,
    DISCOVERING,
    CONNECTING,
    CONNECTED,
    READY,
    DISCONNECTING,
    ERROR
}

data class DeviceContext(
    val deviceId: String? = null,
    val name: String? = null,
    val deviceType: String? = null,
    val batteryPercent: Int? = null,
    val wearing: Boolean = false,
    val connected: Boolean = false,
    val charging: Boolean = false,
    val temperatureCelsius: Float? = null,
    val capabilities: List<String> = emptyList(),
    val transport: String? = null,
    val lastSeenMs: Long? = null,
    val freshness: Freshness = Freshness.UNKNOWN,
    val state: DeviceConnectionState = DeviceConnectionState.DISCONNECTED
)

enum class Freshness {
    FRESH,
    STALE,
    UNKNOWN
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Discovering : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState
    data object Ready : ConnectionState
    data object Disconnecting : ConnectionState
    data class Failed(val reason: String) : ConnectionState
}

sealed interface ObservationSource {
    data object PhoneCamera : ObservationSource
    data object AndroidPhoneMic : ObservationSource
    data object AndroidAssist : ObservationSource
    data object BluetoothHeadset : ObservationSource
    data object AndroidAudioAccessory : ObservationSource
    data object HeyCyanGlasses : ObservationSource
    data object Replay : ObservationSource
    data object Debug : ObservationSource
}
