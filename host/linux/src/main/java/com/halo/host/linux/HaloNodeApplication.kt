package com.halo.host.linux

import com.halo.libraries.common.core.Identifier
import com.halo.libraries.common.core.Version
import com.halo.sdk.runtime.Engine
import com.halo.sdk.runtime.EngineContext
import com.halo.sdk.runtime.EngineHealth
import com.halo.sdk.runtime.EngineMetadata
import com.halo.sdk.runtime.EngineState
import com.halo.sdk.runtime.EventBus
import com.halo.sdk.runtime.Priority
import com.halo.sdk.runtime.RuntimeContext
import com.halo.sdk.runtime.ServiceKey
import com.halo.sdk.runtime.ServiceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Main entry point for the Halo Linux host.
 * 
 * This application runs as a foreground service suitable for systemd supervision.
 * It implements the Halo Engine interface for lifecycle management.
 */
class HaloNodeApplication : Engine {
    private val log = LoggerFactory.getLogger(HaloNodeApplication::class.java)
    
    override val metadata = EngineMetadata(
        id = Identifier("halo-linux-node"),
        version = Version(0, 1, 0),
        description = "Halo Linux Node - Personal Compute Host"
    )
    
    private val _health = MutableStateFlow(EngineHealth.Unknown)
    override val health: StateFlow<EngineHealth> = _health
    
    private val _state = MutableStateFlow<EngineState>(EngineState.Created)
    override val state: StateFlow<EngineState> = _state
    
    override val priority = Priority.NORMAL
    override val dependencies: Set<ServiceKey<*>> = emptySet()
    override val provides: Set<ServiceKey<*>> = emptySet()
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runtimeContext: RuntimeContext? = null
    
    override suspend fun initialize(context: EngineContext) {
        log.info("Initializing Halo Linux Node...")
        _state.value = EngineState.Initializing
        _health.value = EngineHealth.Recovering
        
        this.runtimeContext = context.runtime
        
        // Register core services
        context.registry.register(ServiceKey(EventBus::class), context.runtime.eventBus)
        context.registry.register(ServiceKey(ServiceRegistry::class), context.registry)
        
        log.info("Halo Linux Node initialized")
        _health.value = EngineHealth.Healthy
        _state.value = EngineState.Running
    }
    
    override suspend fun start() {
        log.info("Starting Halo Linux Node...")
        _state.value = EngineState.Running
        _health.value = EngineHealth.Healthy
        
        // Keep the application running
        scope.launch {
            while (_state.value != EngineState.Stopped && _state.value != EngineState.Disposed) {
                try {
                    Thread.sleep(1000)
                } catch (e: InterruptedException) {
                    log.info("Shutdown signal received")
                    break
                }
            }
        }
    }
    
    override suspend fun pause() {
        log.info("Pausing Halo Linux Node...")
        _state.value = EngineState.Paused
    }
    
    override suspend fun resume() {
        log.info("Resuming Halo Linux Node...")
        _state.value = EngineState.Running
    }
    
    override suspend fun stop() {
        log.info("Stopping Halo Linux Node...")
        _state.value = EngineState.Stopped
        _health.value = EngineHealth.Offline
        scope.coroutineContext.cancelChildren()
    }
    
    override suspend fun dispose() {
        log.info("Disposing Halo Linux Node...")
        _state.value = EngineState.Disposed
        _health.value = EngineHealth.Offline
        scope.coroutineContext.cancelChildren()
    }
}

fun main(args: Array<String>) {
    val app = HaloNodeApplication()
    
    // Simple CLI handling
    when (args.firstOrNull()) {
        "--version" -> println("halo-node 0.1.0-SNAPSHOT (commit: unknown)")
        "doctor" -> println("Halo Linux Node: Environment check not yet implemented")
        "status" -> println("Status: ${app.state.value}")
        "start" -> {
            println("Starting Halo Linux Node...")
            // In real implementation, this would run the engine
        }
        "ask" -> {
            val query = args.drop(1).joinToString(" ")
            println("Query: $query")
            println("Response: Inference not yet configured")
        }
        "config" -> println("Configuration: Not yet implemented")
        else -> println("Usage: halo-node [--version|doctor|status|start|ask <query>|config show]")
    }
}