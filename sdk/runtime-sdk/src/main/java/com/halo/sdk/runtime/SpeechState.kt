package com.halo.sdk.runtime

sealed interface SpeechState {
    object Idle : SpeechState
    object Listening : SpeechState
    object WakeDetected : SpeechState
    object Recognizing : SpeechState
    object Thinking : SpeechState
    object Speaking : SpeechState
}
