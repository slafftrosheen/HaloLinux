package com.halo.host.linux

import com.halo.libraries.common.core.Identifier
import com.halo.libraries.common.core.Version
import com.halo.sdk.runtime.Engine
import com.halo.sdk.runtime.EngineContext
import com.halo.sdk.runtime.EngineHealth
import com.halo.sdk.runtime.EngineMetadata
import com.halo.sdk.runtime.EngineState
import com.halo.sdk.runtime.Priority
import com.halo.sdk.runtime.ServiceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.system.exitProcess

/** Host lifecycle contract. The daemon's Linux transport is owned by HaloCli. */
class HaloNodeApplication : Engine {
    override val metadata = EngineMetadata(
        Identifier("halo-linux-node"), Version(0, 1, 0), "Halo Linux Node"
    )
    private val _health = MutableStateFlow(EngineHealth.Unknown)
    override val health: StateFlow<EngineHealth> = _health
    private val _state = MutableStateFlow<EngineState>(EngineState.Created)
    override val state: StateFlow<EngineState> = _state
    override val priority: Priority = Priority.NORMAL
    override val dependencies: Set<ServiceKey<*>> = emptySet()
    override suspend fun initialize(context: EngineContext) {
        check(_state.value == EngineState.Created || _state.value == EngineState.Stopped)
        _state.value = EngineState.Initializing
        _health.value = EngineHealth.Recovering
        // An initialized engine is not a running engine.
        _state.value = EngineState.Stopped
    }
    override suspend fun start() {
        check(_state.value == EngineState.Stopped)
        _state.value = EngineState.Running
        _health.value = EngineHealth.Healthy
    }
    override suspend fun stop() {
        _state.value = EngineState.Stopped
        _health.value = EngineHealth.Offline
    }
    override suspend fun dispose() {
        stop()
        _state.value = EngineState.Disposed
    }
}

fun main(args: Array<String>) {
    val result = HaloCli.run(args)
    if (result != 0) exitProcess(result)
}
