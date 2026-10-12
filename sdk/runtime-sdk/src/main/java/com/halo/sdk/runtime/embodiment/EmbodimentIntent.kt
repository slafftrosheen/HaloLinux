package com.halo.sdk.runtime.embodiment

enum class IntentKind {
    NONE,
    // BuddyBot Locomotion / Action
    MOVE,
    STOP,
    ARM,
    DISARM,
    ACTION,
    SET_MOOD,
    NEXT_PERSONA,
    ACCESSORY,
    
    // HeyCyan / Camera / Media
    CAPTURE_PHOTO,
    START_VIDEO,
    STOP_VIDEO,
    START_AUDIO_RECORDING,
    STOP_AUDIO_RECORDING,
    FETCH_MEDIA_COUNT
}

enum class DriveMode {
    STOPPED,
    FORWARD,
    REVERSE,
    ROTATE_LEFT,
    ROTATE_RIGHT,
    MANUAL_VECTOR // Custom joystick vector
}

enum class ActionId {
    NONE,
    NOD_YES,
    SHAKE_NO,
    CELEBRATE,
    LOOK_LEFT,
    LOOK_RIGHT,
    LOOK_UP,
    LOOK_DOWN,
    SHIVER,
    TILT_HEAD
}

enum class Mood {
    IDLE,
    HAPPY,
    SAD,
    ANGRY,
    SCARED,
    SURPRISED,
    CURIOUS,
    SLEEPY
}

/**
 * Transport-independent representation of an intent for a physical embodiment.
 */
data class EmbodimentIntent(
    /** Stable identifier (UUID/ULID) for correlating execution results. */
    val intentId: String,
    /** Which device this intent should be routed to, if explicitly known. */
    val targetDeviceId: String? = null,
    val kind: IntentKind = IntentKind.NONE,
    val driveMode: DriveMode = DriveMode.STOPPED,
    val vectorX: Float = 0f,
    val vectorY: Float = 0f,
    val durationMs: Int = 0,
    val action: ActionId = ActionId.NONE,
    val mood: Mood = Mood.IDLE,
    val accessoryIndex: Int = 0,
    val accessoryActive: Boolean = false
)
