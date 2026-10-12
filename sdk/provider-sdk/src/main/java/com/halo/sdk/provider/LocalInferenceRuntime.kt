package com.halo.sdk.provider

import kotlinx.coroutines.flow.StateFlow

enum class LocalComputeBackend {
    AUTO,
    GPU,
    CPU,
}

enum class LocalModelFormat {
    GGUF,
    LITERT_LM,
}

enum class LocalModelRuntime {
    LLAMA_CPP,
    LITERT,
}

enum class InferencePriority {
    INTERACTIVE,
    NORMAL,
    BACKGROUND,
}

enum class LocalModelSessionState {
    IDLE,
    LOADING,
    INFERRING,
    UNLOADING,
    ERROR,
}

sealed interface InferenceAdmission {
    data object Granted : InferenceAdmission
    data class Busy(
        val activeRequestId: String?,
        val mode: String?,
        val retryAfterMs: Long? = null,
    ) : InferenceAdmission
    data class Blocked(val reason: String) : InferenceAdmission
}

enum class ReadinessBlocker {
    NO_MODEL_SELECTED,
    MODEL_FILE_MISSING,
    MODEL_UNSUPPORTED,
    LOW_MEMORY,
    RESOURCE_BUSY,
    PROVIDER_NOT_SELECTED,
    DOWNLOAD_INCOMPLETE,
    VALIDATION_PENDING,
}

data class LocalInferenceReadiness(
    val ready: Boolean,
    val selectedModelExists: Boolean,
    val formatSupported: Boolean,
    val memoryAllowed: Boolean,
    val resourceAvailable: Boolean,
    val providerSelected: Boolean,
    val blockers: List<ReadinessBlocker>,
)

sealed interface LocalModelActivityState {
    data object Idle : LocalModelActivityState
    data class Loading(val modelId: String) : LocalModelActivityState
    data class Ready(val modelId: String) : LocalModelActivityState
    data class Generating(val requestId: String, val priority: InferencePriority) : LocalModelActivityState
    data class Benchmarking(val requestId: String) : LocalModelActivityState
    data object Unloading : LocalModelActivityState
    data class Error(val message: String) : LocalModelActivityState
}

data class RuntimeLoadConfig(
    val contextSize: Int = 4096,
    val computeBackend: LocalComputeBackend = LocalComputeBackend.AUTO,
    val gpuLayers: Int = 0,
    val cpuThreads: Int = 4,
    val speculativeDecoding: Boolean? = null,
    val mmprojPath: String? = null,
    /**
     * When true, [contextSize] is advisory only and the runtime must ask the native engine for its
     * own default context window. Required for artifacts whose real context is unknown (imports):
     * forcing a guessed value can make an otherwise valid model fail engine initialization.
     */
    val useModelDefaultContext: Boolean = false,
    val visionEnabled: Boolean = false,
    val audioEnabled: Boolean = false,
)

data class RuntimeLoadResult(
    val activeBackend: LocalComputeBackend,
    val activeGpuLayers: Int,
    val effectiveContextSize: Int,
    val fallbackReason: String? = null,
    /** Context Halo originally asked for, when it differs from [effectiveContextSize]. */
    val requestedContextSize: Int? = null,
    /** Bounded reason the requested context could not be allocated. */
    val contextFallbackReason: String? = null,
)

data class RuntimeConversationMessage(
    val role: Role,
    val content: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
)

data class RuntimeGenerationConfig(
    val prompt: String,
    val systemInstruction: String? = null,
    val temperature: Double = 0.7,
    val topP: Double = 0.9,
    val topK: Int = 40,
    val repeatPenalty: Double = 1.1,
    val maxTokens: Int = 512,
    val priority: InferencePriority = InferencePriority.NORMAL,
    val imageBytes: ByteArray? = null,
    val initialMessages: List<RuntimeConversationMessage> = emptyList(),
    val availableTools: List<ToolDefinition> = emptyList(),
    val thinkingEnabled: Boolean? = null,
    val thinkingTokenBudget: Int? = null,
    /** Encoded audio (for example WAV), not raw microphone PCM. */
    val audioBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RuntimeGenerationConfig) return false
        if (prompt != other.prompt) return false
        if (systemInstruction != other.systemInstruction) return false
        if (temperature != other.temperature) return false
        if (topP != other.topP) return false
        if (topK != other.topK) return false
        if (repeatPenalty != other.repeatPenalty) return false
        if (maxTokens != other.maxTokens) return false
        if (priority != other.priority) return false
        if (initialMessages != other.initialMessages) return false
        if (availableTools != other.availableTools) return false
        if (thinkingEnabled != other.thinkingEnabled) return false
        if (thinkingTokenBudget != other.thinkingTokenBudget) return false
        if (imageBytes != null) {
            if (other.imageBytes == null) return false
            if (!imageBytes.contentEquals(other.imageBytes)) return false
        } else if (other.imageBytes != null) return false
        if (audioBytes != null) {
            if (other.audioBytes == null || !audioBytes.contentEquals(other.audioBytes)) return false
        } else if (other.audioBytes != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = prompt.hashCode()
        result = 31 * result + (systemInstruction?.hashCode() ?: 0)
        result = 31 * result + temperature.hashCode()
        result = 31 * result + topP.hashCode()
        result = 31 * result + topK
        result = 31 * result + repeatPenalty.hashCode()
        result = 31 * result + maxTokens
        result = 31 * result + priority.hashCode()
        result = 31 * result + initialMessages.hashCode()
        result = 31 * result + availableTools.hashCode()
        result = 31 * result + (thinkingEnabled?.hashCode() ?: 0)
        result = 31 * result + (thinkingTokenBudget ?: 0)
        result = 31 * result + (imageBytes?.contentHashCode() ?: 0)
        result = 31 * result + (audioBytes?.contentHashCode() ?: 0)
        return result
    }
}

data class RuntimeGenerationResult(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val tokenCount: Int,
    val timeToFirstTokenMs: Long = 0L,
    val tokensPerSecond: Double = 0.0,
    val durationMs: Long = 0L,
    /**
     * Internal reasoning the runtime deliberately withheld from [text] (for example `<think>`
     * blocks). Diagnostic evidence only: it must never be spoken or shown as the answer.
     */
    val reasoningText: String? = null,
    /** First generation attempt since native engine creation. */
    val coldStart: Boolean? = null,
)

/**
 * Optional runtime capability: a runtime whose effective accelerator can change *after* a load
 * succeeded. Some devices only discover an unusable GPU at first inference, so the runtime may
 * rebuild itself on the CPU. Hosts use this to keep their reported backend and fallback reason
 * truthful instead of repeating the value captured at load time.
 */
interface LocalRuntimeBackendReporting {
    /** Accelerator actually in use, or null when nothing is resident. */
    val activeBackend: LocalComputeBackend?

    /** Bounded reason the requested accelerator was not used, when a fallback occurred. */
    val backendFallbackReason: String?
}

/**
 * Universal runtime abstraction for local on-device machine learning inference.
 * Implemented by the LiteRT runtime adapter.
 */
interface LocalInferenceRuntime {
    val format: LocalModelFormat
    val state: StateFlow<LocalModelSessionState>
    val loadedModelId: String?

    suspend fun loadModel(
        modelId: String,
        modelPath: String,
        config: RuntimeLoadConfig = RuntimeLoadConfig(),
    ): Result<RuntimeLoadResult>

    suspend fun generate(
        config: RuntimeGenerationConfig,
        onToken: suspend (String) -> Unit = {},
    ): Result<RuntimeGenerationResult>

    suspend fun cancelGeneration()

    suspend fun unloadModel(): Result<Unit>

    fun isLoaded(): Boolean
}
