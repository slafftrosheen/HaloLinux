package com.halo.sdk.host

enum class AccessoryControl { ASSISTANT, PRIMARY, SECONDARY, CAMERA, PLAY_PAUSE, NEXT, PREVIOUS, VOLUME_UP, VOLUME_DOWN, CUSTOM }
enum class ControlEvidence { VERIFIED_PLATFORM, VERIFIED_VENDOR, OBSERVED, UNKNOWN }
enum class ControlOrigin { ANDROID_ASSISTANT, SCOPED_MEDIA, WEAR_FOREGROUND, MANAGED_DEVICE, UNKNOWN }
/** Opaque identity and connection epoch. Never put an address or packet in this envelope. */
data class ControlDelivery(
    val endpointId: String, val epoch: String, val eventId: String,
    val origin: ControlOrigin, val evidence: ControlEvidence,
    val classified: Boolean = true, val reliableEdges: Boolean = false,
    val reserved: Boolean = false,
)
enum class AccessoryControlAction { PRESS, RELEASE, SINGLE, DOUBLE, LONG }
enum class AccessoryGesture { TAP, DOUBLE_TAP, LONG_PRESS, SWIPE_FORWARD, SWIPE_BACKWARD }
sealed interface AccessoryControlEvent {
    val timestamp: Long
    data class Button(val control: AccessoryControl, val action: AccessoryControlAction,
        override val timestamp: Long, val delivery: ControlDelivery? = null) : AccessoryControlEvent
    data class Gesture(val gesture: AccessoryGesture, override val timestamp: Long) : AccessoryControlEvent
}

enum class AccessorySemanticAction { INVOKE_ASSISTANT, REQUEST_CAMERA, STOP_SPEECH, NONE }
/** Platform delivery is a prerequisite; this policy never captures global media keys. */
object AccessoryControlPolicy {
    fun action(event: AccessoryControlEvent, speaking: Boolean, mediaControlsEnabled: Boolean): AccessorySemanticAction {
        val button = event as? AccessoryControlEvent.Button ?: return AccessorySemanticAction.NONE
        if (button.action !in setOf(AccessoryControlAction.PRESS, AccessoryControlAction.SINGLE)) return AccessorySemanticAction.NONE
        return when (button.control) {
            AccessoryControl.ASSISTANT -> AccessorySemanticAction.INVOKE_ASSISTANT
            AccessoryControl.CAMERA -> AccessorySemanticAction.REQUEST_CAMERA
            AccessoryControl.PLAY_PAUSE -> if (speaking && mediaControlsEnabled) AccessorySemanticAction.STOP_SPEECH else AccessorySemanticAction.NONE
            else -> AccessorySemanticAction.NONE
        }
    }
}
