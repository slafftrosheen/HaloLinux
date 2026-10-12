package com.halo.sdk.runtime.embodiment

import com.halo.sdk.runtime.DeviceIdentity
import com.halo.sdk.runtime.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/**
 * Storage-backed [DeviceRegistry] for host applications.
 *
 * Device identity and user preferences remain independent from any physical host.
 * Android-specific persistence stays outside the runtime module through [Storage].
 *
 * Storage is a blocking abstraction, so every persistence operation is explicitly
 * dispatched to [Dispatchers.IO]. Callers may safely invoke these suspend methods
 * from UI-scoped coroutines without accidentally performing disk I/O on Main.
 */
class PersistentDeviceRegistry(
    private val storage: Storage,
    private val storagePrefix: String = "halo.device."
) : DeviceRegistry {

    private val mutex = Mutex()

    private companion object {
        const val INDEX_KEY = "device_index"
        const val FIELD_NAME = "name"
        const val FIELD_TYPE = "type"
        const val FIELD_AUTOCONNECT = "auto_connect"
        const val FIELD_FIRMWARE = "firmware"
        const val FIELD_LAST_SEEN = "last_seen"
        const val INDEX_SEPARATOR = "|"
    }

    override suspend fun getSavedDevices(): List<DeviceRecord> = withContext(Dispatchers.IO) {
        mutex.withLock {
            readIds().mapNotNull(::readRecord)
        }
    }

    override suspend fun getDevice(deviceId: String): DeviceRecord? = withContext(Dispatchers.IO) {
        mutex.withLock {
            readRecord(deviceId)
        }
    }

    override suspend fun saveDevice(record: DeviceRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val ids = readIds().toMutableSet()
            ids += record.identity.deviceId
            writeIds(ids)
            writeRecord(record)
        }
    }

    override suspend fun removeDevice(deviceId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val ids = readIds().toMutableSet()
            ids -= deviceId
            writeIds(ids)
            removeRecord(deviceId)
        }
    }

    suspend fun setAutoConnect(deviceId: String, enabled: Boolean): DeviceRecord? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val current = readRecord(deviceId) ?: return@withLock null
                val updated = current.copy(autoConnect = enabled)
                writeRecord(updated)
                updated
            }
        }

    private fun readIds(): List<String> {
        return storage.getString(INDEX_KEY, "")
            .orEmpty()
            .split(INDEX_SEPARATOR)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
    }

    private fun writeIds(ids: Set<String>) {
        storage.putString(INDEX_KEY, ids.sorted().joinToString(INDEX_SEPARATOR))
    }

    private fun readRecord(deviceId: String): DeviceRecord? {
        val base = key(deviceId)
        val name = storage.getString("$base.$FIELD_NAME") ?: return null
        val type = storage.getString("$base.$FIELD_TYPE") ?: return null
        val autoConnect = storage.getBoolean("$base.$FIELD_AUTOCONNECT", true)
        val firmware = storage.getString("$base.$FIELD_FIRMWARE")
        val lastSeen = storage.getString("$base.$FIELD_LAST_SEEN")?.toLongOrNull() ?: 0L

        return DeviceRecord(
            identity = DeviceIdentity(
                deviceId = deviceId,
                displayName = name,
                deviceType = type
            ),
            autoConnect = autoConnect,
            firmwareVersion = firmware,
            lastSeenMs = lastSeen
        )
    }

    private fun writeRecord(record: DeviceRecord) {
        val base = key(record.identity.deviceId)
        storage.putString("$base.$FIELD_NAME", record.identity.displayName)
        storage.putString("$base.$FIELD_TYPE", record.identity.deviceType)
        storage.putBoolean("$base.$FIELD_AUTOCONNECT", record.autoConnect)
        record.firmwareVersion?.let { storage.putString("$base.$FIELD_FIRMWARE", it) }
        storage.putString("$base.$FIELD_LAST_SEEN", record.lastSeenMs.toString())
    }

    private fun removeRecord(deviceId: String) {
        val base = key(deviceId)
        storage.remove("$base.$FIELD_NAME")
        storage.remove("$base.$FIELD_TYPE")
        storage.remove("$base.$FIELD_AUTOCONNECT")
        storage.remove("$base.$FIELD_FIRMWARE")
        storage.remove("$base.$FIELD_LAST_SEEN")
    }

    private fun key(deviceId: String): String = "$storagePrefix${encodeId(deviceId)}"

    /**
     * Encode the complete identifier as deterministic hex. This avoids collisions
     * between identifiers such as `AA:BB` and `AA_BB` caused by character replacement.
     */
    private fun encodeId(deviceId: String): String =
        deviceId.toByteArray(StandardCharsets.UTF_8)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }
}
