package com.halo.sdk.runtime

import com.halo.libraries.common.core.Identifier
import com.halo.libraries.common.core.Version
import kotlinx.coroutines.flow.StateFlow
import kotlin.reflect.KClass

data class EngineMetadata(
    val id: Identifier,
    val version: Version,
    val description: String
)

enum class EngineHealth {
    Healthy, Degraded, Recovering, Failed, Offline, Unknown
}

sealed interface EngineState {
    data object Created : EngineState
    data object Initializing : EngineState
    data object Running : EngineState
    data object Paused : EngineState
    data object Stopped : EngineState
    data object Disposed : EngineState
    data object Unknown : EngineState
    data class Failed(val reason: String) : EngineState
}

interface Engine {
    val metadata: EngineMetadata
    val health: StateFlow<EngineHealth>
    val state: StateFlow<EngineState>
    val priority: Priority get() = Priority.NORMAL
    val dependencies: Set<ServiceKey<*>>
    val provides: Set<ServiceKey<*>> get() = emptySet()

    suspend fun initialize(context: EngineContext)
    suspend fun start()
    suspend fun pause() = Unit
    suspend fun resume() = Unit
    suspend fun stop()
    suspend fun dispose() = Unit
}
