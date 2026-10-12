package com.halo.sdk.runtime

import kotlinx.coroutines.flow.StateFlow

interface SpeechStateObserver {
    val sessionState: StateFlow<SpeechSessionState>
}
