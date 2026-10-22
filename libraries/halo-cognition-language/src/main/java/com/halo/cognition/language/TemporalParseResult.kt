package com.halo.cognition.language

sealed interface TemporalParseResult {
    data class Parsed(
        val value: TemporalValue,
        val original: String,
    ) : TemporalParseResult

    data class Incomplete(
        val original: String,
        val missingField: TemporalField,
    ) : TemporalParseResult

    data class Invalid(
        val original: String,
        val reason: TemporalParseFailure,
    ) : TemporalParseResult
}

enum class TemporalField {
    TIME_OF_DAY,
    DATE,
    AM_PM
}

enum class TemporalParseFailure {
    EMPTY,
    UNSUPPORTED_FORMAT,
    INVALID_HOUR,
    INVALID_MINUTE,
    AMBIGUOUS
}

interface TemporalParser {
    fun parse(text: String): TemporalParseResult
}
