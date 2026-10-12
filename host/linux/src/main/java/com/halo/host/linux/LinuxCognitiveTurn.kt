package com.halo.host.linux

import com.halo.adapters.storage.ConversationRecord
import com.halo.adapters.storage.LinuxStorage
import com.halo.adapters.storage.MessageRecord
import com.halo.sdk.runtime.HaloEvent
import com.halo.sdk.runtime.CognitiveRequestStarted
import com.halo.sdk.runtime.CognitiveReasoningStarted
import com.halo.sdk.runtime.InferenceObserved
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
    val agentRunId: String? = null,
    val canonicalJournalState: String = "NOT_LINKED",
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
    private val recoverySupport: () -> LinuxRecoverySupport = { DisabledRecoverySupport },
) {
    suspend fun ask(text: String): LinuxTextTurnResult {
        require(text.isNotBlank() && text.length <= 32_000)
        val interactionId = UUID.randomUUID().toString()
        val start = clockNs()
        var agentRunId: String? = null
        var mainCommitted = false
        storage.beginTurn(interactionId)
        try {
            runtime.publish(CognitiveRequestStarted(
                requestId = interactionId, interactionId = interactionId
            ))
            runtime.publish(LinuxTurnEvent(
                interactionId = interactionId, stage = LinuxTurnStage.ACCEPTED
            ))
            storage.markTurn(interactionId, "REASONING")
            val target = provider.snapshot()
            agentRunId = recoverySupport().beginTextTurn(
                interactionId, text, target.providerId, target.modelId
            )
            runtime.publish(CognitiveReasoningStarted(
                requestId = interactionId, providerId = target.providerId ?: "unconfigured",
                modelId = target.modelId, interactionId = interactionId
            ))
            runtime.publish(LinuxTurnEvent(interactionId = interactionId, stage = LinuxTurnStage.REASONING))
            val response = provider.generate(text, interactionId)
            recoverySupport().recordTextResponse(agentRunId, response.text)
            val elapsed = TimeUnit.NANOSECONDS.toMillis(clockNs() - start)
            val now = Instant.now()
            val newConversationId = UUID.randomUUID().toString()
            storage.commitTextTurn(interactionId, ConversationRecord(
                id = newConversationId,
                title = text.take(80),
                createdAt = now,
                updatedAt = now,
                providerId = response.providerId,
                modelId = response.modelId,
                metadata = "{}",
                messages = listOf(
                    MessageRecord(UUID.randomUUID().toString(), newConversationId, "user",
                        text, null, null, 0L, 0L, now, "{}"),
                    MessageRecord(UUID.randomUUID().toString(), newConversationId, "assistant",
                        response.text, response.providerId, response.modelId, 0L, elapsed,
                        Instant.now(), "{}")
                )
            ))
            mainCommitted = true
            var journalState = if (agentRunId == null) "NOT_LINKED" else "COMMITTED"
            // Once the main conversation is committed, finalization must not
            // be abandoned halfway by a disconnecting client. Never re-run
            // the model just because the second DB has an incomplete journal.
            withContext(NonCancellable) {
                try {
                    recoverySupport().commitTextDelivery(agentRunId, newConversationId)
                } catch (_: Exception) {
                    journalState = "NEEDS_RECONCILIATION"
                }
                try {
                    runtime.publish(InferenceObserved(
                        requestId = interactionId,
                        providerId = response.providerId,
                        requestedModelId = provider.modelId,
                        resolvedModelId = response.modelId,
                        success = true,
                    ))
                    runtime.publish(LinuxTurnEvent(
                        interactionId = interactionId, stage = LinuxTurnStage.COMMITTED
                    ))
                } catch (_: Exception) {
                    // A failed observer must not erase a durable conversation.
                }
            }
            return LinuxTextTurnResult(interactionId, newConversationId, response.text,
                response.providerId, response.modelId, elapsed, agentRunId, journalState)
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                if (!mainCommitted) {
                    runCatching { storage.markTurn(interactionId, "FAILED", failureType = "CANCELLED") }
                    runCatching { recoverySupport().failTextTurn(agentRunId, "CANCELLED") }
                }
                runtime.publish(InferenceObserved(
                    requestId = interactionId,
                    providerId = provider.snapshot().providerId ?: "unconfigured",
                    requestedModelId = provider.modelId,
                    resolvedModelId = null,
                    success = false,
                ))
                runtime.publish(LinuxTurnEvent(interactionId = interactionId, stage = LinuxTurnStage.FAILED))
            }
            throw cancel
        } catch (error: Exception) {
            if (!mainCommitted) {
                runCatching { storage.markTurn(interactionId, "FAILED", failureType = "FAILED") }
                runCatching { recoverySupport().failTextTurn(agentRunId, "INFERENCE_FAILED") }
            }
            runtime.publish(InferenceObserved(
                requestId = interactionId,
                providerId = provider.snapshot().providerId ?: "unconfigured",
                requestedModelId = provider.modelId,
                resolvedModelId = null,
                success = false,
            ))
            runtime.publish(LinuxTurnEvent(interactionId = interactionId, stage = LinuxTurnStage.FAILED))
            throw error
        }
    }
}
