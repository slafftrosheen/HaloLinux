package com.halo.sdk.runtime

sealed interface InteractionTurnState {
    val interactionId: String?

    data object Idle : InteractionTurnState { override val interactionId: String? = null }
    data class Listening(override val interactionId: String, val sourceDeviceId: String?) : InteractionTurnState
    data class Transcribing(override val interactionId: String) : InteractionTurnState
    data class Preparing(override val interactionId: String) : InteractionTurnState
    data class Generating(override val interactionId: String, val requestId: String) : InteractionTurnState
    data class Speaking(override val interactionId: String, val outputId: String) : InteractionTurnState
    data class Interrupted(override val interactionId: String, val byInteractionId: String?) : InteractionTurnState
    data class Cancelled(override val interactionId: String, val reason: String) : InteractionTurnState
    data class Completed(override val interactionId: String) : InteractionTurnState
    data class Failed(override val interactionId: String, val reason: String) : InteractionTurnState
}

data class BargeInEvidence(
    val physicalActivation: Boolean,
    val localSpeechEnergy: Boolean,
    val localVoicedSpeech: Boolean = false,
    val inputRouteValid: Boolean = false,
    val externalProviderInterrupted: Boolean,
    val timestampMs: Long,
    /* MINING-P1-001 additions. Defaulted so every existing construction still compiles. */
    /** How long voiced speech has been sustained, when the capture layer can measure it. */
    val sustainedSpeechMs: Long? = null,
    /** Optional local semantic hint. Null when no classifier is installed. */
    val semanticInterruptProbability: Float? = null,
    val semanticClassification: BargeInSemanticClass? = null,
    /** Origin device of the speech, when known. Diagnostic only. */
    val sourceDeviceId: String? = null,
)

enum class BargeInDecision { ACCEPT, STOP_EXTERNAL_ONLY, REJECT_ECHO }

/** Naming keeps "interruption" distinct from ordinary conversational behaviour. */
enum class BargeInSemanticClass { INTERRUPT, BACKCHANNEL, UNCERTAIN }

data class BargeInSemanticResult(
    val classification: BargeInSemanticClass,
    val probability: Float?,
    /** Bounded classifier identity, e.g. "vad-heuristics" or an installed model id. */
    val classifierId: String,
)

/**
 * Optional local classifier. Halo is fully functional without one: absence falls back to the
 * existing voiced-speech + sustained-speech heuristics and is reported as "Not installed".
 */
interface BargeInSemanticClassifier {
    val id: String

    val available: Boolean

    suspend fun classify(
        sustainedSpeechMs: Long?,
        localVoicedSpeech: Boolean,
        outputActive: Boolean,
    ): BargeInSemanticResult?
}

/**
 * Tunable interruption policy. Physical activation is never configurable: it always interrupts.
 */
data class BargeInPolicyConfig(
    /** Voiced speech shorter than this is treated as a likely backchannel. */
    val sustainedSpeechThresholdMs: Long = 450L,
    /** When false, semantic hints are ignored even if a classifier is installed. */
    val semanticInterruptionEnabled: Boolean = false,
    val spokenInterruptionEnabled: Boolean = true,
)

object BargeInPolicy {
    fun decide(evidence: BargeInEvidence, outputActive: Boolean): BargeInDecision =
        decide(evidence, outputActive, BargeInPolicyConfig())

    /**
     * MINING-P1-001 decision priority:
     *
     * 1. Physical activation (HeyCyan AI button / explicit device input) — accepted immediately.
     * 2. An installed semantic classifier, when enabled — INTERRUPT accepts, BACKCHANNEL continues
     *    playback, UNCERTAIN falls through to the existing heuristic.
     * 3. Sustained voiced speech on a valid route — accepted once past the threshold.
     * 4. Otherwise stop only the external provider, or reject as echo.
     */
    fun decide(
        evidence: BargeInEvidence,
        outputActive: Boolean,
        config: BargeInPolicyConfig,
    ): BargeInDecision {
        // 1. Physical activation always wins and is never configurable.
        if (evidence.physicalActivation) return BargeInDecision.ACCEPT
        if (!config.spokenInterruptionEnabled) return if (evidence.externalProviderInterrupted)
            BargeInDecision.STOP_EXTERNAL_ONLY else BargeInDecision.REJECT_ECHO
        if (!evidence.inputRouteValid || !evidence.localVoicedSpeech) return if (evidence.externalProviderInterrupted)
            BargeInDecision.STOP_EXTERNAL_ONLY else BargeInDecision.REJECT_ECHO
        // 2. Semantic hint, only when the user enabled it and a classifier actually answered.
        semanticDecision(evidence, outputActive, config)?.let { return it }
        // 3. Existing Halo heuristic, now sustained-speech aware.
        return existingHeuristic(evidence, outputActive, config)
    }

    private fun semanticDecision(
        evidence: BargeInEvidence,
        outputActive: Boolean,
        config: BargeInPolicyConfig,
    ): BargeInDecision? {
        if (!config.semanticInterruptionEnabled || !outputActive) return null
        return when (evidence.semanticClassification) {
            BargeInSemanticClass.INTERRUPT -> BargeInDecision.ACCEPT
            BargeInSemanticClass.BACKCHANNEL -> BargeInDecision.REJECT_ECHO
            BargeInSemanticClass.UNCERTAIN, null -> null
        }
    }

    private fun existingHeuristic(
        evidence: BargeInEvidence,
        outputActive: Boolean,
        config: BargeInPolicyConfig,
    ): BargeInDecision = when {
        outputActive && evidence.localVoicedSpeech && evidence.inputRouteValid &&
            sustainedEnough(evidence, config) -> BargeInDecision.ACCEPT
        evidence.externalProviderInterrupted -> BargeInDecision.STOP_EXTERNAL_ONLY
        else -> BargeInDecision.REJECT_ECHO
    }

    /**
     * Sustained speech is required when the capture layer can measure it. A short backchannel
     * ("mm-hm") on the phone must not necessarily cut off Halo's own speech. When no duration is
     * available, ordinary speech cannot establish interruption authority.
     */
    private fun sustainedEnough(evidence: BargeInEvidence, config: BargeInPolicyConfig): Boolean {
        val sustained = evidence.sustainedSpeechMs ?: return false
        return sustained >= config.sustainedSpeechThresholdMs
    }

    /** Capability truth for Voice settings: never claims a classifier exists. */
    fun semanticAvailabilityLabel(classifier: BargeInSemanticClassifier?): String =
        if (classifier?.available == true) "Smart when available" else "Not installed"

    /** Copy shown in advanced voice diagnostics. */
    const val ADVANCED_HELP = "Physical controls always interrupt immediately. " +
        "Smart interruption only affects ambiguous speech while Halo is speaking."
}

data class InteractionLatencyTrace(
    val interactionId: String,
    val startedAtMs: Long,
    val captureReadyAtMs: Long? = null,
    val captureReceivedAtMs: Long? = null,
    val speechEndedAtMs: Long? = null,
    val transcriptReadyAtMs: Long? = null,
    val modelReadyAtMs: Long? = null,
    val firstTokenAtMs: Long? = null,
    val generationCompleteAtMs: Long? = null,
    val routeReadyAtMs: Long? = null,
    val playbackStartedAtMs: Long? = null,
) {
    val timeToFirstTokenMs: Long? get() = firstTokenAtMs?.minus(startedAtMs)
    val timeToAudibleMs: Long? get() = playbackStartedAtMs?.minus(startedAtMs)
}

data class ObservationFreshnessPolicy(
    val maxAgeMs: Long,
    val newerObservationSupersedes: Boolean,
) {
    fun isFresh(receivedAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - receivedAtMs <= maxAgeMs
}

data class WearableImageObservation(
    val id: String,
    val sourceDeviceId: String,
    val captureRequestedAtMs: Long,
    val deviceReadyAtMs: Long?,
    val exposureAtMs: Long?,
    val receivedAtMs: Long,
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int?,
    val height: Int?,
    val frameSequence: Long,
    val interactionId: String?,
) {
    override fun equals(other: Any?): Boolean = other is WearableImageObservation &&
        id == other.id && sourceDeviceId == other.sourceDeviceId &&
        captureRequestedAtMs == other.captureRequestedAtMs && deviceReadyAtMs == other.deviceReadyAtMs &&
        exposureAtMs == other.exposureAtMs && receivedAtMs == other.receivedAtMs &&
        bytes.contentEquals(other.bytes) && mimeType == other.mimeType && width == other.width &&
        height == other.height && frameSequence == other.frameSequence && interactionId == other.interactionId

    override fun hashCode(): Int = 31 * id.hashCode() + bytes.contentHashCode()
}

data class InteractionTurnChanged(
    override val id: String = java.util.UUID.randomUUID().toString(),
    val state: InteractionTurnState,
    override val timestamp: Long = System.currentTimeMillis(),
) : HaloEvent

data class InteractionLatencyUpdated(
    override val id: String = java.util.UUID.randomUUID().toString(),
    val trace: InteractionLatencyTrace,
    override val timestamp: Long = System.currentTimeMillis(),
) : HaloEvent
