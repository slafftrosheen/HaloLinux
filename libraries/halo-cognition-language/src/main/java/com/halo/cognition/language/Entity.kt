package com.halo.cognition.language

import java.time.LocalTime
import java.time.Instant

sealed interface TemporalValue {
    data class TimeOfDay(
        val time: LocalTime,
        val dayRelation: DayRelation? = null,
    ) : TemporalValue

    data class InstantValue(
        val instant: Instant,
    ) : TemporalValue

    data class DayPart(
        val part: DayPartKind,
        val dayRelation: DayRelation? = null,
    ) : TemporalValue
}

enum class DayRelation {
    TODAY,
    TOMORROW,
}

enum class DayPartKind {
    MORNING,
    AFTERNOON,
    EVENING,
    NIGHT,
}

sealed interface Entity {
    val confidence: Float
}

data class PersonEntity(
    val name: String,
    override val confidence: Float
) : Entity

data class LocationEntity(
    val location: String,
    override val confidence: Float
) : Entity

data class PlaceEntity(
    val name: String,
    override val confidence: Float
) : Entity

data class TimeEntity(
    val value: TemporalValue,
    @Deprecated("Migrate to TemporalValue")
    val expression: String,
    override val confidence: Float
) : Entity

data class UnresolvedTimeEntity(
    val originalText: String,
    val missingField: TemporalField?,
    override val confidence: Float
) : Entity

data class ReminderEntity(
    val message: String,
    val temporalExpression: String?,
    override val confidence: Float
) : Entity

data class DateEntity(
    val expression: String,
    override val confidence: Float
) : Entity

data class NumberEntity(
    val value: Double,
    override val confidence: Float
) : Entity

data class ObjectEntity(
    val name: String,
    override val confidence: Float
) : Entity

data class ApplicationEntity(
    val name: String,
    override val confidence: Float
) : Entity

data class DeviceEntity(
    val name: String,
    override val confidence: Float
) : Entity

data class MediaEntity(
    val title: String,
    override val confidence: Float
) : Entity

data class WebEntity(
    val query: String,
    override val confidence: Float
) : Entity
