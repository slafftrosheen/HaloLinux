package com.halo.sdk.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class Role {
    SYSTEM, USER, ASSISTANT, TOOL
}

data class AIMessage(
    val role: Role,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null
)

enum class AiCapability {
    TEXT,
    IMAGE,
    AUDIO,
    VIDEO,
    MULTIMODAL,
    STREAMING,
    TOOLS
}

/** Provider-neutral intent; capability alone never enables hidden reasoning. */
enum class AiReasoningMode { DEFAULT, DISABLED, ENABLED }

const val LOCAL_INTERACTIVE_REASONING_TOKEN_BUDGET = 64

data class AiRequest(
    val text: String? = null,
    val image: ByteArray? = null,
    val audio: ByteArray? = null,
    val video: ByteArray? = null,
    val mimeTypes: List<String> = emptyList(),
    val modelId: String? = null,
    val priority: InferencePriority = InferencePriority.NORMAL,
    /** Optional hard output cap used by bounded diagnostics and compact turns. */
    val maxOutputTokens: Int? = null,
    /** False when durable continuation already contains this user message in context. */
    val appendTextAsUserMessage: Boolean = true,
    val turnKind: ProviderTurnKind = ProviderTurnKind.INITIAL,
    /** Host presentation policy: auxiliary/child tokens must not become the parent's visible answer. */
    val publishStreamingTokens: Boolean = true,
    /** Optional internal sampler override; providers must explicitly advertise support. */
    val temperatureOverride: Double? = null,
    /** Null preserves direct SDK callers; Runtime always supplies a turn mode. */
    val toolUseMode: ToolUseMode? = null,
    /** Content-free correlation shared by initial execution and fallback. */
    val interactionId: String? = null,
    val reasoningMode: AiReasoningMode = AiReasoningMode.DEFAULT,
    val reasoningTokenBudget: Int? = null,
    /** Transient workload request; null preserves the existing canonical model preferences. */
    val localExecutionProfile: WorkloadExecutionProfile? = null,
)

enum class ProviderTurnKind { INITIAL, TOOL_CONTINUATION }

/** Shared wire MIME selection. Preparation and privacy remain the host/Runtime's responsibility. */
fun AiRequest.imageMimeType(): String = mimeTypes.firstOrNull { it.startsWith("image/") } ?: "image/jpeg"

fun AiRequest.validateForTextGeneration() {
    require(!text.isNullOrBlank()) {
        "Text generation request must contain non-empty text"
    }
}

fun AiRequest.validateAgainst(capabilities: Set<AiCapability>) {
    fun requireSupported(present: Boolean, capability: AiCapability, label: String) {
        if (present && capability !in capabilities) {
            throw AiProviderException(
                AiErrorType.UNSUPPORTED_CAPABILITY,
                "Provider does not support $label input"
            )
        }
    }
    requireSupported(!text.isNullOrBlank(), AiCapability.TEXT, "text")
    requireSupported(image != null, AiCapability.IMAGE, "image")
    requireSupported(audio != null, AiCapability.AUDIO, "audio")
    requireSupported(video != null, AiCapability.VIDEO, "video")
}

data class ToolCall(
    val id: String,
    val functionName: String,
    val arguments: Map<String, Any>
)

data class AiGenerationMetrics(
    val startedAtMs: Long,
    val firstTokenAtMs: Long? = null,
    val completedAtMs: Long? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null
) {
    val timeToFirstTokenMs: Long?
        get() = firstTokenAtMs?.minus(startedAtMs)
    val totalDurationMs: Long?
        get() = completedAtMs?.minus(startedAtMs)
}

data class AiExecutionMetadata(
    val requestId: String,
    val providerId: String,
    val modelId: String?,
    val startedAtMs: Long,
    val completedAtMs: Long?,
    val success: Boolean,
    val finishReason: String?,
    val errorType: AiErrorType? = null,
    val requestedModelId: String? = modelId,
    val resolvedModelId: String? = modelId,
)

data class AiModelRef(
    val providerId: String,
    val modelId: String,
)

enum class AiExecutionLocation { ON_DEVICE, LOCAL_NETWORK, REMOTE }
enum class AiCostClass { FREE, PAID, LOCAL, UNKNOWN }
enum class AiModelKind { CONCRETE, ROUTER }
enum class ModelCatalogueSource {
    LOCAL_INSTALLED, LOCAL_DOWNLOADABLE, REMOTE_LIVE, REMOTE_CACHED, LAN_LIVE
}
enum class AiWireProtocol {
    OPENAI_CHAT_COMPLETIONS, OPENAI_RESPONSES, ANTHROPIC_MESSAGES, GEMINI_GENERATE_CONTENT, OLLAMA, LITERT_NATIVE
}

/**
 * How a remote provider obtains authorization.
 *
 * This is identity-adjacent metadata used by preset wiring and UI. It is
 * deliberately separate from the HTTP transport and from the provider contract
 * so that Groq, Cerebras, xAI, OpenCode Zen and custom OpenAI-compatible
 * endpoints can share one wire implementation.
 */
enum class ProviderAuthKind {
    NONE,
    API_KEY,
    BEARER_TOKEN,
    GOOGLE_OAUTH,
    DEVICE_OAUTH,
    COMPANION
}

/**
 * How a provider is accessed from a cost/billing perspective.
 *
 * This is presentation and policy metadata, not a billing guarantee. Free
 * presence is REMOTE_LIVE evidence from current catalog metadata, not permanent
 * truth baked into Halo.
 */
enum class ProviderAccessKind {
    LOCAL,
    FREE_TIER,
    SUBSCRIPTION,
    PAY_AS_YOU_GO,
    MIXED,
    CUSTOM
}

/**
 * Lifecycle/auth state for a remote provider.
 *
 * This is separate from [ProviderStatus] because OAuth and companion flows have
 * UI-visible intermediate states that do not map cleanly onto provider readiness.
 */
enum class ProviderAuthState {
    /** Credential present; no successful connection has been observed. */
    CONFIGURED,
    NOT_REQUIRED,
    NOT_CONFIGURED,
    AUTHORIZING,
    CONNECTED,
    EXPIRED,
    REFRESHING,
    FAILED
}

/**
 * How a remote provider discovers its model catalog.
 */
enum class ModelDiscoveryMode {
    STATIC_HINTS,
    REMOTE_CATALOG,
    NONE
}

/**
 * Portable descriptor for a remote or gateway provider.
 *
 * This is the reusable identity/protocol/auth/capability description that the
 * provider fabric uses to present presets, drive catalog normalization and feed
 * UI badges/connect/test actions. It is not a live provider instance.
 */
data class RemoteProviderDescriptor(
    val id: String,
    val displayName: String,
    val protocol: AiWireProtocol,
    val authKinds: Set<ProviderAuthKind>,
    val accessKind: ProviderAccessKind,
    val modelDiscovery: ModelDiscoveryMode,
    val capabilities: Set<AiCapability>,
    val executionLocation: AiExecutionLocation = AiExecutionLocation.REMOTE,
    val freeModelHint: String? = null,
)

/**
 * Optional remote cost preference applied after privacy, capability and role
 * constraints. It never overrides LOCAL_ONLY and never silently bills a paid
 * model when no free compatible target exists.
 */
enum class RemoteCostPreference {
    ANY,
    PREFER_FREE,
    FREE_ONLY
}

/**
 * Portable quota/header telemetry parsed by the shared transport.
 *
 * Unknown providers or headers leave fields null. These values are diagnostic
 * and must not be treated as billing truth.
 */
data class ProviderQuotaSnapshot(
    val providerId: String,
    val retrievedAtMs: Long,
    val requestLimit: Int? = null,
    val requestRemaining: Int? = null,
    val tokenLimit: Int? = null,
    val tokenRemaining: Int? = null,
    val retryAfterMs: Long? = null,
    val resetAtMs: Long? = null,
)

/**
 * Auth and connection metadata exposed to UI for a remote provider.
 *
 * This deliberately avoids storing credential values. Non-sensitive labels such
 * as an account/provider name may be surfaced for display only.
 */
data class ProviderAuthInfo(
    val authKind: ProviderAuthKind,
    val authState: ProviderAuthState,
    val lastAuthFailure: String? = null,
    val accountLabel: String? = null,
    val expiresAtMs: Long? = null,
    val quotaSnapshot: ProviderQuotaSnapshot? = null,
)

/**
 * Transport security outcome for credential-bearing requests.
 */
enum class CredentialTransportResult {
    /** Credentials may be sent. */
    SAFE,
    /** Public cleartext transport must not receive credentials. */
    INSECURE_CREDENTIAL_TRANSPORT
}

/**
 * Built-in provider identity constants used by the SDK fabric and presets.
 *
 * IDs are stable because remote model identity is normalized to
 * [AiModelRef.providerId] alongside modelId.
 */
object RemoteProviderIds {
    const val GROQ = "groq"
    const val CEREBRAS = "cerebras"
    const val OPENCODE_ZEN = "opencode.zen"
    const val XAI = "xai"
    const val OPENCODE_COMPANION = "companion.opencode"
    const val CUSTOM_OPENAI_COMPATIBLE = "custom.openaicompatible"
}

/**
 * Credential transport security check.
 *
 * Credentials may only be sent over HTTPS or explicitly trusted local transport
 * (loopback, LAN, Tailscale, or *.local). Public cleartext HTTP must not receive
 * credentials.
 */
object CredentialTransportPolicy {
    fun evaluate(url: String, allowInsecureLocal: Boolean): CredentialTransportResult {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return CredentialTransportResult.INSECURE_CREDENTIAL_TRANSPORT
        if (uri.scheme != "http" && uri.scheme != "https") return CredentialTransportResult.INSECURE_CREDENTIAL_TRANSPORT
        if (uri.scheme == "https") return CredentialTransportResult.SAFE
        if (!allowInsecureLocal) return CredentialTransportResult.INSECURE_CREDENTIAL_TRANSPORT
        val host = uri.host?.lowercase() ?: return CredentialTransportResult.INSECURE_CREDENTIAL_TRANSPORT
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") return CredentialTransportResult.SAFE
        if (host == "0.0.0.0") return CredentialTransportResult.SAFE
        if (host.endsWith(".local")) return CredentialTransportResult.SAFE
        if (host.startsWith("10.") || host.startsWith("172.") || host.startsWith("192.168.")) return CredentialTransportResult.SAFE
        if (host == "tailscale" || host.endsWith(".ts.net")) return CredentialTransportResult.SAFE
        return CredentialTransportResult.INSECURE_CREDENTIAL_TRANSPORT
    }
}

data class AiModelRuntimeMetadata(
    val ref: AiModelRef,
    val location: AiExecutionLocation,
    val installed: Boolean?,
    val resident: Boolean?,
    val requiresNetwork: Boolean,
    val requiresCredential: Boolean,
    val costClass: AiCostClass = AiCostClass.UNKNOWN,
    /** Transport detail for diagnostics/adapters; cognition must not branch on it. */
    val wireProtocol: AiWireProtocol? = null,
)

data class AiModelCatalogEntry(
    val model: AiModel,
    val runtime: AiModelRuntimeMetadata,
    val kind: AiModelKind = AiModelKind.CONCRETE,
    val source: ModelCatalogueSource,
)

data class AiProviderFailure(val type: AiErrorType, val httpStatus: Int?, val retryAfterMs: Long?, val providerCode: String? = null)

data class AiModelCatalogState(
    val models: List<AiModelCatalogEntry> = emptyList(),
    val refreshingProviders: Set<String> = emptySet(),
    val providerErrors: Map<String, String> = emptyMap(),
    val refreshedAtMs: Long? = null,
    val providerFailures: Map<String, AiProviderFailure> = emptyMap(),
)

data class AiExecutionTargetSnapshot(
    val providerId: String,
    val requestedModelId: String,
    val resolvedModelId: String? = null,
    val executionLocation: AiExecutionLocation,
)

data class AiProviderDiagnostic(
    val providerId: String,
    val displayName: String,
    val state: ProviderStatus.State,
    val message: String?,
    val capabilities: Set<AiCapability>,
    val selected: Boolean,
    val selectedModelId: String?,
    val local: Boolean
)

data class AiRuntimeSnapshot(
    val selectedProviderId: String?,
    val selectedModelId: String?,
    val routingPolicy: AiRoutingPolicy,
    val providers: List<AiProviderDiagnostic>
)

data class AiResponse(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val finishReason: String? = null,
    val providerId: String,
    val modelId: String? = null,
    val metrics: AiGenerationMetrics? = null,
    val requestedModelId: String? = modelId,
    val resolvedModelId: String? = modelId,
    /** Physical/runtime backend when the provider can report it (for example GPU or CPU). */
    val executionBackend: String? = null,
    /** Opaque host-owned auxiliary body reference; providers do not resolve it. */
    val payloadRefId: String? = null,
    /** Actual local engine lifecycle, null when the provider cannot report it. */
    val coldStart: Boolean? = null,
)

sealed interface AiStreamEvent {
    data object Started : AiStreamEvent
    data class Token(val text: String) : AiStreamEvent
    data class ToolCallDelta(
        val index: Int,
        val name: String? = null,
        val argumentsDelta: String? = null,
        /** Provider-issued identity. It must not be synthesized before execution. */
        val id: String? = null,
    ) : AiStreamEvent
    data class Completed(val response: AiResponse) : AiStreamEvent
    data class Failed(val error: Throwable) : AiStreamEvent
}

sealed interface CognitiveSessionState {
    data object Idle : CognitiveSessionState
    data object PreparingContext : CognitiveSessionState
    data class Reasoning(val providerId: String, val modelId: String? = null) : CognitiveSessionState
    data class ExecutingTool(val toolId: String) : CognitiveSessionState
    data class Streaming(val text: String) : CognitiveSessionState
    data class Completed(val response: AiResponse) : CognitiveSessionState
    data class Failed(val message: String, val partialText: String? = null, val conversationId: String? = null,
        val interactionId: String? = null, val requestId: String? = null) : CognitiveSessionState
}

/**
 * Common metadata/diagnostic surface shared by all provider kinds.
 * Concrete execution contracts are intentionally split between AiInferenceProvider
 * and AiHandoffProvider.
 */
interface AiProvider {
    val id: String
    val displayName: String
    val isLocal: Boolean
    val status: StateFlow<ProviderStatus>

    /** Implementations must internally dispatch any disk, network, or native work. */
    suspend fun getStatus(): ProviderStatus = status.value
    suspend fun getCapabilities(): Set<AiCapability>
    /** Main-safe diagnostic probe; callers do not choose an IO dispatcher. */
    suspend fun testConnection(): Boolean = getStatus().state == ProviderStatus.State.READY
    /** Host has changed endpoint/auth settings. Must not perform network IO. */
    fun configurationChanged() = Unit
}

sealed interface ProviderReadiness {
    data object Unconfigured : ProviderReadiness
    data object ConfiguredCold : ProviderReadiness
    data object Loading : ProviderReadiness
    data object Ready : ProviderReadiness
    data class Failed(val reason: String) : ProviderReadiness
}

data class ProviderStatus(
    val state: State,
    val message: String? = null,
    val readiness: ProviderReadiness = ProviderReadiness.Unconfigured,
    val rejectionReason: TargetRejectionReason? = null,
    val cooldown: ProviderCooldown? = null,
) {
    enum class State {
        READY,
        STANDBY,
        LOADING,
        /** Configured and usable, but a request is in flight right now. */
        BUSY,
        UNCONFIGURED,
        OFFLINE,
        MODEL_MISSING,
        UNAVAILABLE,
        ERROR
    }

    companion object {
        val READY = ProviderStatus(State.READY)
        val UNAVAILABLE = ProviderStatus(State.UNAVAILABLE)
        val UNCONFIGURED = ProviderStatus(State.UNCONFIGURED)
        val OFFLINE = ProviderStatus(State.OFFLINE)
        val MODEL_MISSING = ProviderStatus(State.MODEL_MISSING)
        val LOADING = ProviderStatus(State.LOADING)
    }
}

data class AiModel(
    val id: String,
    val displayName: String,
    val providerId: String,
    val inputCapabilities: Set<AiCapability>,
    val contextLength: Long?,
    val supportsStreaming: Boolean,
    val supportsTools: Boolean,
    val isFree: Boolean = false,
    /** Physical execution boundary; local-network endpoints still receive data off device. */
    val executionLocation: AiExecutionLocation = AiExecutionLocation.REMOTE,
    val capabilityEvidence: Map<AiCapability, CapabilityEvidence> = emptyMap(),
    /** Exact artifact/catalogue capability, independent of the requested reasoning policy. */
    val supportsThinking: Boolean = false,
)

// Legacy compatibility (to be removed once migrated)
data class AIConversation(
    val id: String,
    val messages: MutableList<AIMessage>
)

data class AIRequestLegacy(
    val conversation: AIConversation,
    val temperature: Float = 0.2f,
    val maxTokens: Int = 512
)

data class AIResponseLegacy(
    val text: String,
    val finishReason: String,
    val latencyMs: Long
)
