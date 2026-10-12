package com.halo.host.linux

import java.nio.file.Path
import org.json.JSONObject

/**
 * Host boundary: the default Pi node has no canonical agent DB. The opt-in
 * canonical module adds classification only, never autonomous resumption.
 */
interface LinuxRecoverySupport : AutoCloseable {
    /** Called exactly once after the daemon lock is held. */
    suspend fun prepare() = Unit
    suspend fun inspect(): JSONObject
}

object DisabledRecoverySupport : LinuxRecoverySupport {
    override suspend fun inspect(): JSONObject = JSONObject()
        .put("state", "NOT_LINKED")
        .put("automatic_replay", false)
        .put("tool_execution", false)
    override fun close() = Unit
}

object LinuxRecoverySupportFactory {
    fun open(dataDir: Path): LinuxRecoverySupport {
        val implementation = try {
            Class.forName("com.halo.host.linux.CanonicalHostRecoverySupport")
        } catch (_: ClassNotFoundException) {
            return DisabledRecoverySupport
        }
        val instance = implementation.getDeclaredConstructor(Path::class.java)
            .newInstance(dataDir)
        return instance as LinuxRecoverySupport
    }
}
