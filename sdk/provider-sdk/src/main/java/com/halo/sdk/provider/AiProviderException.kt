package com.halo.sdk.provider

/**
 * Normalized semantic failures for AI providers.
 */
enum class AiErrorType {
    PROVIDER_UNAVAILABLE,
    MODEL_UNAVAILABLE,
    MODEL_NOT_SELECTED,
    MODEL_NOT_FOUND,
    MODEL_LOAD_FAILED,
    MODEL_NOT_READY,
    INVALID_REQUEST,
    GENERATION_FAILED,
    EMPTY_RESPONSE,
    CANCELLED,
    AUTHENTICATION_FAILED,
    RATE_LIMITED,
    SESSION_CONFLICT,
    TIMEOUT,
    NETWORK_UNAVAILABLE,
    NETWORK_ERROR,
    INVALID_RESPONSE,
    UNSUPPORTED_CAPABILITY,
    CONTEXT_TOO_LARGE,
    LOCAL_MODEL_UNAVAILABLE,
    CAPABILITY_DISABLED,
    UNKNOWN_ERROR
}

enum class Recoverability {
    RETRY_IMMEDIATELY,
    RETRY_AFTER,
    USER_ACTION_REQUIRED,
    SESSION_CONFLICT,
    PERMANENT,
    UNKNOWN,
}

data class AiFailureDetails(
    val retryAfterMs: Long? = null,
    val providerCode: String? = null,
    val httpStatus: Int? = null,
    val activeSessionId: String? = null,
    val activeMode: String? = null,
    val recoverability: Recoverability = Recoverability.UNKNOWN,
)

data class ProviderCooldown(
    val untilEpochMs: Long,
    val reason: AiErrorType,
) {
    fun remainingMs(nowEpochMs: Long = System.currentTimeMillis()): Long =
        (untilEpochMs - nowEpochMs).coerceAtLeast(0L)

    fun isActive(nowEpochMs: Long = System.currentTimeMillis()): Boolean =
        untilEpochMs > nowEpochMs
}

/**
 * Semantic exception thrown by AI providers.
 */
class AiProviderException(
    val type: AiErrorType,
    message: String,
    val details: AiFailureDetails = AiFailureDetails(),
    cause: Throwable? = null,
    val partialText: String? = null
) : RuntimeException(message, cause) {
    constructor(
        type: AiErrorType,
        message: String,
        cause: Throwable?,
    ) : this(type, message, AiFailureDetails(), cause)

    fun sanitizedSummary(requestedModel: String? = null): String = buildString {
        append("Last request: ").append(type.name.lowercase().replace('_', ' '))
        details.httpStatus?.let { append(" | HTTP: ").append(it) }
        append(" | Recovery: ").append(when (details.recoverability) {
            Recoverability.RETRY_IMMEDIATELY -> "retryable"
            Recoverability.RETRY_AFTER -> "retry after backoff"
            Recoverability.USER_ACTION_REQUIRED -> "user action required"
            Recoverability.SESSION_CONFLICT -> "session conflict"
            Recoverability.PERMANENT -> "permanent"
            Recoverability.UNKNOWN -> "unknown"
        })
        requestedModel?.let { append(" | Model: ").append(it) }
    }
}
