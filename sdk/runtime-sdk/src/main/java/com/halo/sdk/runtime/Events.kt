package com.halo.sdk.runtime

import java.util.UUID
import com.halo.sdk.runtime.embodiment.ExecutionResult

data class WakeWordDetected(override val id: String = UUID.randomUUID().toString(), override val timestamp: Long) : HaloEvent
data class SpeechRecognized(
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long,
    val text: String,
    val locale: String,
    val input: SpeechInputTurn? = null,
    val confidence: Float? = null,
    val quality: RecognitionQuality = RecognitionQuality(),
    val metrics: SpeechRecognitionMetrics? = null
) : HaloEvent

data class SpeechRejected(
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    val text: String,
    val reason: String,
    val captureId: String? = null,
    val quality: RecognitionQuality = RecognitionQuality(),
    val metrics: SpeechRecognitionMetrics? = null,
    val input: SpeechInputTurn? = null
) : HaloEvent

data class RuntimeFactResolved(
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    val transactionId: String,
    val factType: String,
    val valueSource: String = "system_clock"
) : HaloEvent
data class IntentRecognized(override val id: String = UUID.randomUUID().toString(), override val timestamp: Long, val intent: HaloIntent) : HaloEvent
data class AIResponseGenerated(override val id: String = UUID.randomUUID().toString(), override val timestamp: Long, val response: String) : HaloEvent

// Cognitive Session Events
data class CognitiveRequestStarted(override val id: String = UUID.randomUUID().toString(), val requestId: String, override val timestamp: Long = System.currentTimeMillis(), val interactionId: String? = null) : HaloEvent
data class CognitiveReasoningStarted(override val id: String = UUID.randomUUID().toString(), val requestId: String, val providerId: String, val modelId: String?, val interactionId: String? = null, override val timestamp: Long = System.currentTimeMillis()) : HaloEvent
data class CognitiveTokenReceived(override val id: String = UUID.randomUUID().toString(), val requestId: String, val token: String, val interactionId: String? = null, override val timestamp: Long = System.currentTimeMillis()) : HaloEvent
/**
 * Provider-neutral generation timing carried on runtime events.
 *
 * This deliberately lives in runtime-sdk rather than reusing the provider-owned metrics type:
 * provider-sdk depends on runtime-sdk, so referencing a provider type from an event here would
 * invert the dependency and create a module cycle.
 */
data class CognitiveGenerationMetrics(
    val startedAtMs: Long,
    val firstTokenAtMs: Long? = null,
    val completedAtMs: Long? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
) {
    /** Real time-to-first-token, not time-to-model-loaded. */
    val timeToFirstTokenMs: Long? get() = firstTokenAtMs?.minus(startedAtMs)
    val totalDurationMs: Long? get() = completedAtMs?.minus(startedAtMs)
}

data class CognitiveCompleted(override val id: String = UUID.randomUUID().toString(), val requestId: String, val response: String?, val interactionId: String? = null, val metrics: CognitiveGenerationMetrics? = null, override val timestamp: Long = System.currentTimeMillis()) : HaloEvent
/** Privacy-safe inference evidence: never contains prompt or response content. */
data class InferenceObserved(
    override val id: String = UUID.randomUUID().toString(),
    val requestId: String,
    val providerId: String,
    val requestedModelId: String?,
    val resolvedModelId: String?,
    val success: Boolean,
    val backend: String? = null,
    val metrics: CognitiveGenerationMetrics? = null,
    override val timestamp: Long = System.currentTimeMillis(),
) : HaloEvent
data class CognitiveFailed(
    override val id: String = UUID.randomUUID().toString(),
    val requestId: String,
    val message: String,
    val stage: String = "UNKNOWN",
    val type: String? = null,
    val providerId: String? = null,
    val modelId: String? = null,
    val userRecoverable: Boolean = false,
    val interactionId: String? = null,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class ConversationTurnCompleted(
    override val id: String = UUID.randomUUID().toString(),
    val turnId: String,
    val userText: String,
    val assistantText: String,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class DeviceCommand(override val id: String = UUID.randomUUID().toString(), override val timestamp: Long, val command: String) : HaloEvent
data class TtsRequested(
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    val text: String,
    val transactionId: String,
    val target: SpeechOutputTarget = SpeechOutputTarget.Phone,
    val fallback: SpeechOutputFallback = SpeechOutputFallback.ALLOW_PHONE,
    val sourceCaptureId: String? = null,
    val interactionId: String? = null,
    val speechRate: Double = 1.0,
    val language: String? = null,
) : HaloEvent

data class TtsRangeStarted(val transactionId: String, val start: Int, val end: Int,
    override val id: String = UUID.randomUUID().toString(), override val timestamp: Long = System.currentTimeMillis()) : HaloEvent

data class TtsStarted(
    override val id: String = UUID.randomUUID().toString(),
    val transactionId: String,
    val target: SpeechOutputTarget = SpeechOutputTarget.Phone,
    val textLength: Int = 0,
    val interactionId: String? = null,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class TtsCompleted(
    override val id: String = UUID.randomUUID().toString(),
    val transactionId: String,
    val target: SpeechOutputTarget = SpeechOutputTarget.Phone,
    val interactionId: String? = null,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class TtsCancelled(
    override val id: String = UUID.randomUUID().toString(),
    val transactionId: String,
    val target: SpeechOutputTarget = SpeechOutputTarget.Phone,
    val interactionId: String? = null,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class TtsFailed(
    override val id: String = UUID.randomUUID().toString(),
    val transactionId: String,
    val target: SpeechOutputTarget = SpeechOutputTarget.Phone,
    val reason: String,
    val message: String = reason,
    val interactionId: String? = null,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

// Context Events
data class ContextAssembled(
    override val id: String = UUID.randomUUID().toString(),
    val requestId: String,
    val conversationCount: Int,
    val recentEventCount: Int,
    val memoryCount: Int,
    val toolCount: Int,
    val omissions: Int,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

// Tool Execution Events
data class ToolCallRequested(
    override val id: String = UUID.randomUUID().toString(),
    val requestId: String,
    val toolCallId: String,
    val toolId: String,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class ToolCallAuthorized(
    override val id: String = UUID.randomUUID().toString(),
    val requestId: String,
    val toolCallId: String,
    val toolId: String,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class ToolCallCompleted(
    override val id: String = UUID.randomUUID().toString(),
    val requestId: String,
    val toolCallId: String,
    val toolId: String,
    val success: Boolean,
    val failureCode: String? = null,
    val actionTruth: String? = null,
    val durationMs: Long? = null,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class ToolCallRejected(
    override val id: String = UUID.randomUUID().toString(),
    val requestId: String,
    val toolCallId: String,
    val toolId: String,
    val reason: String,
    val reasonCode: String? = null,
    val retryable: Boolean? = null,
    val requiresUserAction: Boolean? = null,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

// Runtime Lifecycle Events
sealed interface RuntimeLifecycleEvent : HaloEvent
data class RuntimeBooting(override val id: String = UUID.randomUUID().toString(), override val timestamp: Long = System.currentTimeMillis()) : RuntimeLifecycleEvent
data class RuntimeStarted(override val id: String = UUID.randomUUID().toString(), override val timestamp: Long = System.currentTimeMillis()) : RuntimeLifecycleEvent
data class RuntimeStopping(override val id: String = UUID.randomUUID().toString(), override val timestamp: Long = System.currentTimeMillis()) : RuntimeLifecycleEvent
data class RuntimeStopped(override val id: String = UUID.randomUUID().toString(), override val timestamp: Long = System.currentTimeMillis()) : RuntimeLifecycleEvent

// Engine Lifecycle Events
sealed interface EngineLifecycleEvent : HaloEvent {
    val engineId: String
}
data class EngineInitializing(override val id: String = UUID.randomUUID().toString(), override val engineId: String, override val timestamp: Long = System.currentTimeMillis()) : EngineLifecycleEvent
data class EngineStarted(override val id: String = UUID.randomUUID().toString(), override val engineId: String, override val timestamp: Long = System.currentTimeMillis()) : EngineLifecycleEvent
data class EngineFailed(override val id: String = UUID.randomUUID().toString(), override val engineId: String, val reason: String, override val timestamp: Long = System.currentTimeMillis()) : EngineLifecycleEvent
data class EngineStopped(override val id: String = UUID.randomUUID().toString(), override val engineId: String, override val timestamp: Long = System.currentTimeMillis()) : EngineLifecycleEvent

// Provider Events
data class ProviderConnected(override val id: String = UUID.randomUUID().toString(), val providerId: String, override val timestamp: Long = System.currentTimeMillis()) : HaloEvent
    data class ProviderAuthStateChanged(override val id: String = UUID.randomUUID().toString(), val providerId: String, val authState: String, override val timestamp: Long = System.currentTimeMillis()) : HaloEvent


// Planning & Execution Events
data class GoalCreated(override val id: String = UUID.randomUUID().toString(), val goalId: String, override val timestamp: Long = System.currentTimeMillis()) : HaloEvent
data class PlanGenerated(override val id: String = UUID.randomUUID().toString(), val planId: String, override val timestamp: Long = System.currentTimeMillis()) : HaloEvent
data class ExecutionStarted(override val id: String = UUID.randomUUID().toString(), val planId: String, override val timestamp: Long = System.currentTimeMillis()) : HaloEvent
data class ExecutionFinished(override val id: String = UUID.randomUUID().toString(), val planId: String, override val timestamp: Long = System.currentTimeMillis()) : HaloEvent
data class OutcomePublished(override val id: String = UUID.randomUUID().toString(), val outcome: String, override val timestamp: Long = System.currentTimeMillis()) : HaloEvent

// Embodiment Events
data class DeviceIdentity(
    val deviceId: String,
    val deviceType: String,
    val displayName: String
)

data class EmbodimentExecutionEvent(
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    val device: DeviceIdentity,
    val result: ExecutionResult
) : HaloEvent

// Metrics Events
data class StageMetricsPublished(
    override val id: String = UUID.randomUUID().toString(),
    val observationId: String,
    val stage: RuntimeStage,
    val startedAt: Long,
    val finishedAt: Long,
    val latency: Long,
    val success: Boolean,
    val error: String? = null,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

// Validation Events
data class TraceValidationFailed(
    override val id: String = UUID.randomUUID().toString(),
    val observationId: String,
    val message: String,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class TraceValidationWarning(
    override val id: String = UUID.randomUUID().toString(),
    val observationId: String,
    val message: String,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

// Recovery Events
data class RecoveryStarted(
    override val id: String = UUID.randomUUID().toString(),
    val observationId: String,
    val recoveryAction: String,
    val attempt: Int,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class RecoveryCompleted(
    override val id: String = UUID.randomUUID().toString(),
    val observationId: String,
    val recoveryAction: String,
    val success: Boolean,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class RecoveryFailed(
    override val id: String = UUID.randomUUID().toString(),
    val observationId: String,
    val recoveryAction: String,
    val reason: String,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

// Semantic Tracing & Reliability Events (Batch 08)
data class ObservationReceived(
    override val id: String = UUID.randomUUID().toString(),
    val observationId: String,
    val source: String,
    val content: String,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class InterpretationProduced(
    override val id: String = UUID.randomUUID().toString(),
    val observationId: String,
    val interpretation: String,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class IntentCreated(
    override val id: String = UUID.randomUUID().toString(),
    val intentId: String,
    val intentKind: String,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class IntentResolved(
    override val id: String = UUID.randomUUID().toString(),
    val intentId: String,
    val deviceId: String,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class IntentDispatched(
    override val id: String = UUID.randomUUID().toString(),
    val intentId: String,
    val deviceId: String,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class DeviceConnected(
    override val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val sessionId: String,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class DeviceReady(
    override val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class DeviceDisconnected(
    override val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class CapabilityUpdated(
    override val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val capabilities: List<String>,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class RuntimeStateChanged(
    override val id: String = UUID.randomUUID().toString(),
    val deviceId: String,
    val state: String,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class ProviderRequest(
    override val id: String = UUID.randomUUID().toString(),
    val requestId: String,
    val providerId: String,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class ProviderResult(
    override val id: String = UUID.randomUUID().toString(),
    val requestId: String,
    val success: Boolean,
    val reason: String? = null,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class ProviderFailure(
    override val id: String = UUID.randomUUID().toString(),
    val requestId: String,
    val providerId: String,
    val reason: String,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class AiRoleResolutionEvent(
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    val workloadKind: String,
    val requestedRole: String,
    val resolvedRole: String,
    val bindingMode: String,
    val preferredProvider: String?,
    val preferredModel: String?
) : HaloEvent

data class AiTargetResolvedEvent(
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    val resolvedProvider: String,
    val resolvedModel: String?,
    val isLocal: Boolean,
    val fallbackUsed: Boolean
) : HaloEvent

data class RemoteProviderRequestEvent(
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    val interactionId: String?,
    val requestId: String,
    val providerId: String,
    val modelId: String?,
    val routingPolicy: String,
    val role: String,
    val reason: String
) : HaloEvent
