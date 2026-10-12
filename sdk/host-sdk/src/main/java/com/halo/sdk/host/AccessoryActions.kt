package com.halo.sdk.host

/** This allowlist cannot represent a tool invocation or confirmation. */
enum class AccessoryAction {
    TalkToHalo, StopSpeaking, RepeatLastAnswer, TakePhoto, CaptureAndAsk, OpenRecentCapture,
    CreateNote, OpenStudy, OpenCurrentInteraction, ContinueOnThisDevice, ReviewRoutine, SystemDefault, DoNothing
}
data class AccessoryActionSelection(val action: AccessoryAction, val routineId: String? = null)
enum class AccessoryActionOutcome { EXECUTED, OPENED_FOR_REVIEW, BLOCKED, UNAVAILABLE, IGNORED, FAILED }
data class AccessoryActionResult(val outcome: AccessoryActionOutcome, val reason: String)
data class ControlBinding(val control: AccessoryControl, val gesture: AccessoryControlAction,
    val selection: AccessoryActionSelection)
data class ControlEndpoint(val id: String, val deviceClass: String, val epoch: String,
    val durable: Boolean, val connected: Boolean,
    val supported: Set<Pair<AccessoryControl, AccessoryControlAction>>,
    val availableActions: Set<AccessoryAction>)

object ConfigurableAccessoryControlPolicy {
    fun select(event: AccessoryControlEvent.Button, endpoint: ControlEndpoint,
        device: List<ControlBinding>, defaults: List<ControlBinding>): AccessoryActionSelection {
        val d = event.delivery
        if (d == null || d.endpointId != endpoint.id || d.epoch != endpoint.epoch || !endpoint.connected)
            return AccessoryActionSelection(AccessoryAction.DoNothing)
        if (d.reserved || event.control in setOf(AccessoryControl.VOLUME_UP, AccessoryControl.VOLUME_DOWN))
            return AccessoryActionSelection(AccessoryAction.SystemDefault)
        if (d.evidence !in setOf(ControlEvidence.VERIFIED_PLATFORM, ControlEvidence.VERIFIED_VENDOR) ||
            (event.control to event.action) !in endpoint.supported) return AccessoryActionSelection(AccessoryAction.DoNothing)
        return (device + defaults).firstOrNull { it.control == event.control && it.gesture == event.action }
            ?.selection ?: AccessoryActionSelection(AccessoryAction.SystemDefault)
    }
}
