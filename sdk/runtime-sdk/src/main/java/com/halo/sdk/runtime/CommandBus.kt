package com.halo.sdk.runtime
import kotlinx.coroutines.flow.Flow

sealed interface RuntimeCommand

data class SpeakText(val text: String) : RuntimeCommand
data class LaunchApplication(val packageName: String) : RuntimeCommand
data class SaveMemory(val memory: String) : RuntimeCommand
data object StartListening : RuntimeCommand
/** Known control activation; legacy StartListening remains UNKNOWN. */
data class StartListeningWithActivation(val activation: SpeechActivationKind, val conversationId: String? = null) : RuntimeCommand
data object StopListening : RuntimeCommand
data object CancelSpeech : RuntimeCommand

interface CommandBus {
    suspend fun dispatch(command: RuntimeCommand)
    fun commands(): Flow<RuntimeCommand>
}
