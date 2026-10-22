package com.halo.cognition.language

import java.time.LocalTime

class TemporalExpressionParser : TemporalParser {

    private val wordHours = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12
    )

    private val wordMinutes = mapOf(
        "zero" to 0, "o'clock" to 0, "fifteen" to 15, "thirty" to 30, "forty five" to 45, "forty-five" to 45
    )

    override fun parse(text: String): TemporalParseResult {
        val original = text.trim()
        var normalized = normalize(original)
            .replace(Regex("^(set\\s+)?(an?\\s+)?alarm\\s+(for\\s+)?"), "")
            .trim()

        if (normalized.isBlank()) {
            return TemporalParseResult.Incomplete(
                original = original,
                missingField = TemporalField.TIME_OF_DAY
            )
        }

        var remaining = normalized.replace("in the evening", "pm").replace("in the afternoon", "pm").replace("in the morning", "am")

        // 1. Day relation
        var dayRelation: DayRelation? = null
        if (remaining.contains("tomorrow")) {
            dayRelation = DayRelation.TOMORROW
            remaining = remaining.replace("tomorrow", "").trim()
        } else if (remaining.contains("today") || remaining.contains("tonight")) {
            dayRelation = DayRelation.TODAY
            if (remaining.contains("today")) {
                remaining = remaining.replace("today", "").trim()
            }
            if (remaining == "tonight") {
                remaining = "night"
            } else if (remaining.startsWith("tonight ")) {
                remaining = remaining.removePrefix("tonight ").trim()
            }
        }

        // Clean up prepositions
        remaining = remaining.replace(Regex("^at\\s+"), "").replace(Regex("\\s+at\\s+"), " ").trim()

        // 2. DayPart check if no specific clock time remains
        if (remaining.isBlank() && normalized.contains("tonight")) {
            return TemporalParseResult.Parsed(
                value = TemporalValue.DayPart(DayPartKind.NIGHT, dayRelation),
                original = original
            )
        }

        val partKind = when (remaining) {
            "morning" -> DayPartKind.MORNING
            "afternoon" -> DayPartKind.AFTERNOON
            "evening" -> DayPartKind.EVENING
            "night" -> DayPartKind.NIGHT
            else -> null
        }

        if (partKind != null) {
            return TemporalParseResult.Parsed(
                value = TemporalValue.DayPart(partKind, dayRelation),
                original = original
            )
        }

        if (remaining.isBlank()) {
            return TemporalParseResult.Incomplete(
                original = original,
                missingField = TemporalField.TIME_OF_DAY
            )
        }

        // Handle "tonight at nine" -> remaining was "tonight at nine", tonight removed -> "nine"
        if (normalized.contains("tonight") && remaining == "night") {
            return TemporalParseResult.Parsed(
                value = TemporalValue.DayPart(DayPartKind.NIGHT, dayRelation),
                original = original
            )
        }

        // 3. Clock time parsing
        var isPm = false
        var isAm = false
        if (remaining.endsWith("pm") || remaining.endsWith("p.m.")) {
            isPm = true
            remaining = remaining.substringBefore("p").trim()
        } else if (remaining.endsWith("am") || remaining.endsWith("a.m.")) {
            isAm = true
            remaining = remaining.substringBefore("a").trim()
        } else if (normalized.contains("tonight") || normalized.contains("evening")) {
            isPm = true
            remaining = remaining.replace("tonight", "").replace("evening", "").trim()
        }

        var hour: Int? = null
        var minute: Int = 0

        if (remaining.contains(":")) {
            val parts = remaining.split(":")
            if (parts.size == 2) {
                val h = parts[0].trim().toIntOrNull()
                val m = parts[1].trim().toIntOrNull()
                if (h == null || h !in 0..23) {
                    return TemporalParseResult.Invalid(original, TemporalParseFailure.INVALID_HOUR)
                }
                if (m == null || m !in 0..59) {
                    return TemporalParseResult.Invalid(original, TemporalParseFailure.INVALID_MINUTE)
                }
                hour = h
                minute = m
            } else {
                return TemporalParseResult.Invalid(original, TemporalParseFailure.UNSUPPORTED_FORMAT)
            }
        } else {
            val tokens = remaining.split(Regex("\\s+"))
            val hToken = tokens.getOrNull(0) ?: ""
            val mToken = tokens.drop(1).joinToString(" ")

            hour = hToken.toIntOrNull() ?: wordHours[hToken]

            if (hour == null) {
                return TemporalParseResult.Invalid(original, TemporalParseFailure.UNSUPPORTED_FORMAT)
            }

            if (mToken.isNotBlank()) {
                val minVal = mToken.toIntOrNull() ?: wordMinutes[mToken]
                if (minVal != null && minVal in 0..59) {
                    minute = minVal
                } else {
                    return TemporalParseResult.Invalid(original, TemporalParseFailure.INVALID_MINUTE)
                }
            }
        }

        if (hour !in 0..23) {
            return TemporalParseResult.Invalid(original, TemporalParseFailure.INVALID_HOUR)
        }

        if ((isPm || isAm) && hour !in 1..12) {
            return TemporalParseResult.Invalid(original, TemporalParseFailure.INVALID_HOUR)
        }

        if (isPm && hour < 12) {
            hour += 12
        } else if (isAm && hour == 12) {
            hour = 0
        }

        return try {
            val time = LocalTime.of(hour, minute)
            TemporalParseResult.Parsed(
                value = TemporalValue.TimeOfDay(time, dayRelation),
                original = original
            )
        } catch (e: Exception) {
            TemporalParseResult.Invalid(original, TemporalParseFailure.UNSUPPORTED_FORMAT)
        }
    }

    private fun normalize(text: String): String {
        return text.trim().lowercase().trimEnd('?', '.', '!', ',')
    }
}
