package com.halo.sdk.provider

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Bounded transport evidence. Never retain arbitrary server-provided strings. */
fun providerRetryAfterMs(value: String?, nowMs: Long = System.currentTimeMillis()): Long? {
    val text = value?.trim()?.takeIf { it.length in 1..128 } ?: return null
    text.toDoubleOrNull()?.let { seconds ->
        if (!seconds.isFinite() || seconds < 0) return null
        return (seconds * 1000).coerceAtMost(86_400_000.0).toLong()
    }
    return runCatching {
        (ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMs)
            .coerceIn(0, 86_400_000)
    }.getOrNull()
}

fun safeProviderErrorCode(value: String?): String? = value?.takeIf {
    it in SAFE_PROVIDER_CODES || it.toIntOrNull()?.let { code -> code in 400..599 } == true
}

private val SAFE_PROVIDER_CODES = setOf(
    "invalid_api_key", "insufficient_quota", "permission_denied", "model_not_found",
    "rate_limit_exceeded", "billing_hard_limit_reached", "context_length_exceeded",
    "authentication_error", "permission_error", "invalid_request_error", "rate_limit_error",
    "overloaded_error", "not_found_error", "api_error",
    "INVALID_ARGUMENT", "UNAUTHENTICATED", "PERMISSION_DENIED", "NOT_FOUND", "RESOURCE_EXHAUSTED",
    "FAILED_PRECONDITION", "INTERNAL", "UNAVAILABLE", "DEADLINE_EXCEEDED",
)
