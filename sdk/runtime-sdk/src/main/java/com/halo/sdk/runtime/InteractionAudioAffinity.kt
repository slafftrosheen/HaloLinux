package com.halo.sdk.runtime

/**
 * MINING-P0-001 the explicit interaction audio-route lifetime contract:
 *
 *     interaction route lifetime  >  capture lifetime  >  utterance lifetime
 *
 * A wearable-origin interaction must not silently lose its wearable route merely because STT
 * capture ended or inference began. Releasing the microphone lease therefore does not erase this
 * binding; only the interaction finishing, being cancelled, an explicit policy handoff, or host
 * teardown does.
 *
 * This is a *description* of the route the router already resolved, not a second router: the audio
 * session coordinator that owns the leases owns this record too.
 */
enum class InteractionAudioRouteState {
    /** Target resolved and endpoint verified. */
    BOUND,

    /** Target chosen but the platform endpoint is not ready yet (pairing / waiting). */
    PENDING,

    /** Endpoint was bound and then disappeared mid-interaction. Never silently re-routed. */
    LOST,

    /** Policy refused the route and phone fallback was disallowed. */
    UNAVAILABLE,

    /** Interaction completed; the record is retained as "most recent" for diagnostics. */
    ENDED,
}

data class InteractionAudioAffinity(
    val interactionId: String,
    /** Opaque origin device id. Prefer [originLabel] for display. */
    val originDeviceId: String?,
    val originKind: AudioDeviceKind?,
    val inputRoute: SpeechInputRoute,
    val intendedTarget: SpeechOutputTarget,
    val fallback: SpeechOutputFallback,
    /** Human label of the endpoint actually verified, when one was. Never a raw address. */
    val resolvedEndpointLabel: String?,
    val routeState: InteractionAudioRouteState,
    val boundAtMs: Long,
    val lastTransitionAtMs: Long,
) {
    /** Privacy-safe origin label: display name, else device kind, else the phone itself. */
    val originLabel: String
        get() = when {
            inputRoute is SpeechInputRoute.Phone -> "Phone"
            else -> {
                val device = inputRoute as? SpeechInputRoute.Device
                device?.displayName?.takeIf { it.isNotBlank() }
                    ?: device?.kind?.name?.lowercase()?.replace('_', ' ')
                    ?: originKind?.name?.lowercase()?.replace('_', ' ')
                    ?: "External device"
            }
        }

    /** Privacy-safe intended-output label. Never a MAC address. */
    val intendedTargetLabel: String
        get() = when (val target = intendedTarget) {
            is SpeechOutputTarget.Phone -> "Phone"
            SpeechOutputTarget.HostDefault -> "System audio"
            is SpeechOutputTarget.Device ->
                target.displayName?.takeIf { it.isNotBlank() }
                    ?: target.kind.name.lowercase().replace('_', ' ')
        }

    val fallbackPolicyLabel: String
        get() = when (fallback) {
            SpeechOutputFallback.DISALLOW_PHONE -> "Phone disabled"
            SpeechOutputFallback.ALLOW_PHONE -> "Phone allowed"
        }

    val allowsPhoneFallback: Boolean get() = fallback == SpeechOutputFallback.ALLOW_PHONE

    fun summaryLine(): String =
        "interaction=$interactionId origin=$originLabel intended=$intendedTargetLabel " +
            "endpoint=${resolvedEndpointLabel ?: "unresolved"} state=$routeState fallback=$fallbackPolicyLabel"
}
