package com.halo.sdk.provider

import kotlinx.coroutines.flow.StateFlow

data class SpeechProviderSelection(val id: String)

interface SpeechProviderSelector {
    val sttSelection: StateFlow<SpeechProviderSelection>
    val ttsSelection: StateFlow<SpeechProviderSelection>

    fun selectStt(id: String)
    fun selectTts(id: String)
}
