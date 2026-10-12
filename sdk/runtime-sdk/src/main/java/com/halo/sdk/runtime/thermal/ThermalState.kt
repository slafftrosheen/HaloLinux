package com.halo.sdk.runtime.thermal

import kotlinx.coroutines.flow.StateFlow

/**
 * MINING-P1-001 thermal governor contract.
 *
 * Portable: no `android.os.PowerManager` may appear in runtime modules. Hosts publish platform
 * thermal truth through [ThermalStateSource] and the Runtime decides what work may start, continue
 * or must be released.
 *
 * Thermal pressure and memory pressure are separate signals. They may both inform eviction, but a
 * hot device is not a full device and neither signal may be reported as the other.
 */
enum class ThermalSeverity {
    NORMAL,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY,

    /** The host could not read thermal state. Treated as NORMAL for gating, reported honestly. */
    UNKNOWN,
    ;

    /**
     * Ordering helper: true when this is at least as severe as [other].
     * [UNKNOWN] is not an ordering point, so it only ever matches itself.
     */
    fun atLeast(other: ThermalSeverity): Boolean = when {
        this == UNKNOWN || other == UNKNOWN -> this == other
        else -> ordinal >= other.ordinal
    }

    val isElevated: Boolean get() = this != NORMAL && this != UNKNOWN

    val label: String
        get() = when (this) {
            NORMAL -> "Normal"
            LIGHT -> "Light"
            MODERATE -> "Moderate"
            SEVERE -> "Severe"
            CRITICAL -> "Critical"
            EMERGENCY -> "Emergency"
            UNKNOWN -> "Unknown"
        }
}

data class ThermalState(
    val severity: ThermalSeverity = ThermalSeverity.UNKNOWN,
    /** Platform status code when the host can report one. Opaque outside the host. */
    val platformStatus: Int? = null,
    val recordedAtMs: Long = 0L,
    /** Bounded host label, for example "power_manager" or "unavailable". Never user content. */
    val source: String = "unavailable",
) {
    val isKnown: Boolean get() = severity != ThermalSeverity.UNKNOWN
}

interface ThermalStateSource {
    val state: StateFlow<ThermalState>
}

/** Which kind of work is asking to run. Thermal policy differs per class. */
enum class ThermalWorkloadClass {
    /** A user is waiting: an active interactive run is allowed to reach a safe boundary. */
    INTERACTIVE,

    /** Unattended/background work: the first thing to defer under any thermal pressure. */
    BACKGROUND,
}

enum class ThermalReason {
    NONE,

    /** Allowed, but recorded so Activity and diagnostics can show the gate. */
    RECORDED_LIGHT,

    /** Background automatic workload preference should be reduced. */
    DEFER_AUTOMATIC_BACKGROUND,

    /** Starting new expensive background work is refused. */
    BLOCKED_BACKGROUND,

    /** Local heavyweight generation must stop at the next safe boundary. */
    CANCEL_LOCAL_GENERATION,

    /** Idle native resources should be released immediately. */
    RELEASE_IDLE_RESOURCES,

    /** Thermal state is unknown; the gate is permissive and says so. */
    UNKNOWN_PERMISSIVE,
}

data class ThermalDecision(
    val allowed: Boolean,
    val reason: ThermalReason,
    val severity: ThermalSeverity,
    /** Bounded, content-free explanation suitable for a log line or Activity summary. */
    val summary: String,
) {
    val elevated: Boolean get() = reason != ThermalReason.NONE && reason != ThermalReason.UNKNOWN_PERMISSIVE
}

/**
 * Source behaviour for the thermal governor.
 *
 * Halo never forces a cloud fallback because the device is hot: `LOCAL_ONLY` stays `LOCAL_ONLY`,
 * and an explicit local selection returns a thermal-unavailable result instead of silently
 * changing provider.
 */
object ThermalPolicy {

    /** Whether work of [workload] may start now. */
    fun evaluateBeforeStart(severity: ThermalSeverity, workload: ThermalWorkloadClass): ThermalDecision =
        when (severity) {
            ThermalSeverity.UNKNOWN -> ThermalDecision(
                allowed = true,
                reason = ThermalReason.UNKNOWN_PERMISSIVE,
                severity = severity,
                summary = "Thermal state unavailable; allowing work",
            )
            ThermalSeverity.NORMAL -> ThermalDecision(
                allowed = true,
                reason = ThermalReason.NONE,
                severity = severity,
                summary = "Thermal normal",
            )
            ThermalSeverity.LIGHT -> ThermalDecision(
                allowed = true,
                reason = ThermalReason.RECORDED_LIGHT,
                severity = severity,
                summary = "Thermal light; recorded only",
            )
            ThermalSeverity.MODERATE -> when (workload) {
                ThermalWorkloadClass.INTERACTIVE -> ThermalDecision(
                    allowed = true,
                    reason = ThermalReason.DEFER_AUTOMATIC_BACKGROUND,
                    severity = severity,
                    summary = "Thermal moderate; interactive allowed, background preference reduced",
                )
                ThermalWorkloadClass.BACKGROUND -> ThermalDecision(
                    allowed = true,
                    reason = ThermalReason.DEFER_AUTOMATIC_BACKGROUND,
                    severity = severity,
                    summary = "Thermal moderate; background work should wait",
                )
            }
            ThermalSeverity.SEVERE -> when (workload) {
                ThermalWorkloadClass.INTERACTIVE -> ThermalDecision(
                    allowed = true,
                    reason = ThermalReason.DEFER_AUTOMATIC_BACKGROUND,
                    severity = severity,
                    summary = "Thermal severe; active interactive run may reach a safe boundary",
                )
                ThermalWorkloadClass.BACKGROUND -> ThermalDecision(
                    allowed = false,
                    reason = ThermalReason.BLOCKED_BACKGROUND,
                    severity = severity,
                    summary = "Thermal severe; new expensive background work refused",
                )
            }
            ThermalSeverity.CRITICAL, ThermalSeverity.EMERGENCY -> when (workload) {
                ThermalWorkloadClass.INTERACTIVE -> ThermalDecision(
                    allowed = false,
                    reason = ThermalReason.CANCEL_LOCAL_GENERATION,
                    severity = severity,
                    summary = "Thermal ${severity.label.lowercase()}; local heavyweight generation stops at a safe boundary",
                )
                ThermalWorkloadClass.BACKGROUND -> ThermalDecision(
                    allowed = false,
                    reason = ThermalReason.BLOCKED_BACKGROUND,
                    severity = severity,
                    summary = "Thermal ${severity.label.lowercase()}; background work refused",
                )
            }
        }

    /** Whether an already-running run must stop at its next safe boundary. */
    fun shouldStopActiveRun(severity: ThermalSeverity): Boolean =
        severity == ThermalSeverity.CRITICAL || severity == ThermalSeverity.EMERGENCY

    /** Whether idle native resources should be released right now. */
    fun shouldReleaseIdleResources(severity: ThermalSeverity): Boolean =
        severity == ThermalSeverity.CRITICAL || severity == ThermalSeverity.EMERGENCY

    /** Whether future automatic (unattended) workload preference should be reduced. */
    fun shouldReduceAutomaticWorkload(severity: ThermalSeverity): Boolean =
        severity == ThermalSeverity.MODERATE ||
            severity == ThermalSeverity.SEVERE ||
            severity == ThermalSeverity.CRITICAL ||
            severity == ThermalSeverity.EMERGENCY

    /** User-facing wording. Never exposes enum names on Home or in speech. */
    fun userMessage(severity: ThermalSeverity): String = when (severity) {
        ThermalSeverity.CRITICAL, ThermalSeverity.EMERGENCY ->
            "Local processing paused because the phone is too hot."
        ThermalSeverity.SEVERE ->
            "I will hold off on heavier work because the phone is getting hot."
        ThermalSeverity.MODERATE ->
            "I am keeping background work light because the phone is warm."
        else -> "The phone is warm, so I am keeping this task short."
    }
}
