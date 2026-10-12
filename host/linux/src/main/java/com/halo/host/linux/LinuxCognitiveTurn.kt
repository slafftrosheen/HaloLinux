package com.halo.host.linux

import com.halo.adapters.storage.ConversationRecord
import com.halo.adapters.storage.LinuxStorage
import com.halo.adapters.storage.MessageRecord
import com.halo.sdk.runtime.HaloEvent
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class LinuxTurnStage { ACCEPTED, REASONING, COMMITTED, FAILED }

/** No user text, model payload, or other private content in the event bus. */
data class LinuxTurnEvent(
    override val id: String = UUID.randomUUID().toString(),
    val interactionId: String,
    val stage: LinuxTurnStage,
    override val timestamp: Long = System.currentTimeMillis(),
) : HaloEvent

data class LinuxTextTurnResult(
    val interactionId: String,
    val conversationId: String,
    val text: String,
    val providerId: String,
    val modelId: String?,
    val latencyMs: Long,
)

/**
 * Initial Halo cognition boundary for Linux: a text-only, side-effect-free turn.
 * This uses canonical SDK request/context contracts and Halo's runtime event bus.
 * It is deliberately NOT the canonical durable AgentRunCoordinator yet.
 */
class LinuxCognitiveTurn(
    private val provider: LinuxProviderLifecycle,
    private val storage: LinuxStorage,
    private val runtime: LinuxRuntimeBridge,
    private val clockNs: () -> Long = System::nanoTime,
) {
    suspend fun ask(text: String): LinuxTextTurnResult {
        require(text.isNotBlank() && text.length <= 32_000)
        val interactionId = UUID.randomUUID().toString()
        val start = clockNs()
        storage.beginTurn(interactionId)
        runtime.publish(LinuxTurnEvent(interactionId = interactionId, stage = LinuxTurnStage.ACCEPTED))
        try {
            storage.markTurn(interactionId, "REASONING")
            runtime.publish(LinuxTurnEvent(interactionId = interactionId, stage = LinuxTurnStage.REASONING))
            val response = provider.generate(text, interactionId)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(clockNs() - start)
            val now = Instant.now()
            val conversationId = UUID.randomUUID().toString()
            storage.storeConversation(ConversationRecord(
                id = conversationId,
                title = text.take(80),
                createdAt = now,
                updatedAt = now,
                providerId = response.providerId,
                modelId = response.modelId,
                metadata = "{}",
                messages = listOf(
                    MessageRecord(UUID.randomUUID().toString(), conversationId, "user",
                        text, null, null, 0L, 0L, now, "{}"),
                    MessageRecord(UUID.randomUUID().toString(), conversationId, "assistant",
                        response.text, response.providerId, response.modelId, 0L, elapsed,
                        Instant.now(), "{}")
                )
            ))
            storage.markTurn(interactionId, "COMMITTED", conversationId = conversationId)
            runtime.publish(LinuxTurnEvent(interactionId = interactionId, stage = LinuxTurnStage.COMMITTED))
            return LinuxTextTurnResult(interactionId, conversationId, response.text,
                response.providerId, response.modelId, elapsed)
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                runCatching { storage.markTurn(interactionId, "FAILED", failureType = "CANCELLED") }
                runtime.publish(LinuxTurnEvent(interactionId = interactionId, stage = LinuxTurnStage.FAILED))
            }
            throw cancel
        } catch (error: Exception) {
            runCatching { storage.markTurn(interactionId, "FAILED", failureType = "FAILED") }
            runtime.publish(LinuxTurnEvent(interactionId = interactionId, stage = LinuxTurnStage.FAILED))
            throw error
        }
    }
}
