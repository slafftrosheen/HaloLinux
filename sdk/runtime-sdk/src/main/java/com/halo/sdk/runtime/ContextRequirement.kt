package com.halo.sdk.runtime

enum class ContextRequirement {
    NONE,
    OPTIONAL,
    REQUIRED
}

enum class VisualFreshness(val maxAgeMs: Long) {
    CURRENT_VIEW(10_000L),
    RECENT_CAPTURE(60_000L),
    REFERENCE(Long.MAX_VALUE)
}
