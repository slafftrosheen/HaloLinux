package com.halo.sdk.runtime.embodiment

import com.halo.sdk.runtime.DeviceIdentity

/**
 * A persistent record of a device known to the system.
 */
data class DeviceRecord(
    val identity: DeviceIdentity,
    val autoConnect: Boolean = true,
    val firmwareVersion: String? = null,
    val lastSeenMs: Long = 0L,
    val descriptor: DeviceDescriptor = DeviceDescriptor.fromDeviceType(identity.deviceType)
)

/**
 * The source of truth for devices the user has saved.
 */
interface DeviceRegistry {
    /**
     * Get all saved devices.
     */
    suspend fun getSavedDevices(): List<DeviceRecord>

    /**
     * Get a specific device by ID.
     */
    suspend fun getDevice(deviceId: String): DeviceRecord?

    /**
     * Save or update a device record.
     */
    suspend fun saveDevice(record: DeviceRecord)

    /**
     * Remove a saved device.
     */
    suspend fun removeDevice(deviceId: String)
}

/**
 * In-memory implementation of DeviceRegistry for testing and initial offline use.
 */
class InMemoryDeviceRegistry : DeviceRegistry {
    private val devices = mutableMapOf<String, DeviceRecord>()

    override suspend fun getSavedDevices(): List<DeviceRecord> {
        return devices.values.toList()
    }

    override suspend fun getDevice(deviceId: String): DeviceRecord? {
        return devices[deviceId]
    }

    override suspend fun saveDevice(record: DeviceRecord) {
        devices[record.identity.deviceId] = record
    }

    override suspend fun removeDevice(deviceId: String) {
        devices.remove(deviceId)
    }
}
