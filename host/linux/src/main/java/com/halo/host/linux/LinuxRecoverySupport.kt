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
    /** Optional, local-only durable text journaling. No tool execution or replay. */
    suspend fun beginTextTurn(
        interactionId: String, text: String, providerId: String?, modelId: String?,
    ): String? = null
    suspend fun recordTextResponse(runId: String?, response: String) = Unit
    suspend fun commitTextDelivery(runId: String?, conversationId: String) = Unit
    suspend fun failTextTurn(runId: String?, reasonCode: String) = Unit
    suspend fun backup(destination: Path): JSONObject =
        throw UnsupportedOperationException("Canonical agent journal is not linked")
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
