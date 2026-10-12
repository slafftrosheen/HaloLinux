package com.halo.sdk.runtime

sealed interface SpeechSessionState {
    data object Idle : SpeechSessionState
    data object PassiveListening : SpeechSessionState
    data object Triggered : SpeechSessionState
    data object Capturing : SpeechSessionState
    data object Recognizing : SpeechSessionState
    data object Thinking : SpeechSessionState
    data object Handoff : SpeechSessionState
    data object Processing : SpeechSessionState
    data object Speaking : SpeechSessionState
    data object Recovering : SpeechSessionState
    data class Error(val reason: String) : SpeechSessionState
}
