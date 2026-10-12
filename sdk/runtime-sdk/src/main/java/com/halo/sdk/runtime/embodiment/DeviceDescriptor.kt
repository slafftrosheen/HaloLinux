package com.halo.sdk.runtime.embodiment

enum class DeviceKind(val label: String) {
    HEYCYAN_GLASSES("HeyCyan Glasses"),
    BUDDYBOT("BuddyBot"),
    UNKNOWN("Unknown");

    companion object {
        fun fromDeviceType(deviceType: String): DeviceKind = when {
            deviceType.contains("BuddyBot", ignoreCase = true) -> BUDDYBOT
            deviceType.contains("HeyCyan", ignoreCase = true) ||
                deviceType.contains("Glasses", ignoreCase = true) ||
                deviceType == "HEYCYAN_GLASSES" -> HEYCYAN_GLASSES
            else -> UNKNOWN
        }
    }
}

data class DeviceCapabilities(
    val hasCamera: Boolean = false,
    val hasMicrophone: Boolean = false,
    val hasSpeaker: Boolean = false,
    val hasDisplay: Boolean = false,
    val hasLocomotion: Boolean = false,
    val hasVideoRecording: Boolean = false,
    val hasAudioRecording: Boolean = false
) {
    companion object {
        val HEYCYAN_GLASSES = DeviceCapabilities(
            hasCamera = true,
            hasMicrophone = true,
            hasSpeaker = true,
            hasDisplay = false,
            hasLocomotion = false,
            hasVideoRecording = true,
            hasAudioRecording = true
        )
        val BUDDYBOT = DeviceCapabilities(
            hasCamera = true,
            hasMicrophone = true,
            hasSpeaker = true,
            hasDisplay = true,
            hasLocomotion = true,
            hasVideoRecording = false,
            hasAudioRecording = false
        )
    }
}

data class DeviceDescriptor(
    val kind: DeviceKind,
    val capabilities: DeviceCapabilities
) {
    companion object {
        fun fromKind(kind: DeviceKind): DeviceDescriptor = DeviceDescriptor(
            kind = kind,
            capabilities = when (kind) {
                DeviceKind.HEYCYAN_GLASSES -> DeviceCapabilities.HEYCYAN_GLASSES
                DeviceKind.BUDDYBOT -> DeviceCapabilities.BUDDYBOT
                DeviceKind.UNKNOWN -> DeviceCapabilities()
            }
        )

        fun fromDeviceType(deviceType: String): DeviceDescriptor =
            fromKind(DeviceKind.fromDeviceType(deviceType))
    }
}

data class DeviceHealth(
    val state: State = State.HEALTHY,
    val message: String? = null
) {
    enum class State { HEALTHY, WARNING, ERROR, UNBOUND }
}

sealed interface DeviceTelemetry {
    val batteryPercent: Int?
    val batteryCharging: Boolean
    val batteryTimestampMs: Long
    val health: DeviceHealth
    val firmwareVersion: String?

    data class Glasses(
        val batteryLevel: Int? = null,
        val charging: Boolean = false,
        val timestampMs: Long = 0L,
        override val health: DeviceHealth = DeviceHealth(),
        override val firmwareVersion: String? = null,
        val wearState: String = "Unknown",
        val volumeMusic: Int? = null,
        val volumeCall: Int? = null
    ) : DeviceTelemetry {
        override val batteryPercent get() = batteryLevel
        override val batteryCharging get() = charging
        override val batteryTimestampMs get() = timestampMs
    }

    data class Robot(
        override val batteryPercent: Int? = null,
        override val batteryCharging: Boolean = false,
        override val batteryTimestampMs: Long = 0L,
        override val health: DeviceHealth = DeviceHealth(),
        override val firmwareVersion: String? = null,
        val safetyState: String = "Healthy",
        val safetyFault: String? = null,
        val forwardMotionBlocked: Boolean = false
    ) : DeviceTelemetry
}
