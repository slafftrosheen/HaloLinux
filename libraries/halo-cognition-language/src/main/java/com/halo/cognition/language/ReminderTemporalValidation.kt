package com.halo.cognition.language

import java.time.LocalTime

/** Deterministic validation and canonicalization shared with reminder clarification. */
object ReminderTemporalValidation {
    fun canonical(expression: String, currentTime: LocalTime = LocalTime.now()): String? {
        val text = expression.trim().lowercase().trimEnd('.', '!', '?')
        // The reminder backend also supports bounded positive relative durations.
        Regex("(?:in\\s+)?([0-9]+)\\s*(minutes?|hours?)").matchEntire(text)?.let {
            val amount = it.groupValues[1].toLongOrNull() ?: return null
            if (amount !in 1..525600) return null
            return "in $amount ${it.groupValues[2]}"
        }
        val parsed = TemporalExpressionParser().parse(text) as? TemporalParseResult.Parsed ?: return null
        val value = parsed.value
        val time: LocalTime
        val day: DayRelation?
        when (value) {
            is TemporalValue.TimeOfDay -> {
                // A bare one-to-twelve hour lacks a defensible AM/PM choice.
                if (ambiguous(text)) return null
                time = value.time
                day = value.dayRelation
            }
            is TemporalValue.InstantValue -> return null
            is TemporalValue.DayPart -> {
                time = when (value.part) {
                    DayPartKind.MORNING -> LocalTime.of(9, 0)
                    DayPartKind.AFTERNOON -> LocalTime.of(15, 0)
                    DayPartKind.EVENING -> LocalTime.of(19, 0)
                    DayPartKind.NIGHT -> LocalTime.of(21, 0)
                }
                day = value.dayRelation
            }
        }
        if (day == DayRelation.TODAY && !time.isAfter(currentTime)) return null
        return day?.name?.lowercase()?.plus(" ").orEmpty() + "at $time"
    }

    fun ambiguous(expression: String): Boolean {
        val text = expression.lowercase()
        if ("tomorrow" in text) return false
        if (Regex("am|pm|morning|afternoon|evening|night|:[0-9]{2}").containsMatchIn(text)) return false
        val hour = text.replace(Regex("today|tomorrow|at"), "").trim()
        return hour in setOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve") || hour.toIntOrNull() in 1..12
    }
}
