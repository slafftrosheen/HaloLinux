package com.halo.host.linux

import com.halo.adapters.storage.LinuxStorage
import com.halo.adapters.storage.SqliteAgentRunStore
import com.halo.adapters.storage.SqliteRuntimePreferences
import com.halo.libraries.common.core.Identifier
import com.halo.libraries.common.core.Version
import com.halo.sdk.provider.AiCapability
import com.halo.sdk.provider.AiContext
import com.halo.sdk.provider.AiInferenceProvider
import com.halo.sdk.provider.AiModel
import com.halo.sdk.provider.AiProviderException
import com.halo.sdk.provider.AiRequest
import com.halo.sdk.provider.AiResponse
import com.halo.sdk.provider.AiStreamEvent
import com.halo.sdk.provider.AiExecutionLocation
import com.halo.sdk.provider.AiWireProtocol
import com.halo.sdk.provider.Credential
import com.halo.sdk.provider.CredentialProvider
import com.halo.sdk.provider.ModelDiscoveryMode
import com.halo.sdk.provider.OpenAiCompatibleTransport
import com.halo.sdk.provider.ProviderAuthKind
import com.halo.sdk.provider.ProviderConfigurationDescriptor
import com.halo.sdk.provider.ProviderConfigurationField
import com.halo.sdk.provider.ProviderConfigurationReporting
import com.halo.sdk.provider.ProviderFieldKind
import com.halo.sdk.provider.ProviderStatus
import com.halo.sdk.provider.ProviderReadiness
import com.halo.sdk.runtime.AgentRun
import com.halo.sdk.runtime.AgentRunEvent
import com.halo.sdk.runtime.AgentRunId
import com.halo.sdk.runtime.AgentRunStatus
import com.halo.sdk.runtime.AgentRunStore
import com.halo.sdk.runtime.AgentStep
import com.halo.sdk.runtime.AgentStepId
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
import com.halo.sdk.runtime.StructuredStorage
import com.halo.sdk.runtime.StructuredStorageConfig
import com.halo.sdk.runtime.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.util.UUID

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
    
    // Composition root services
    private lateinit var structuredStorage: StructuredStorage
    private lateinit var preferencesStorage: Storage
    private lateinit var agentRunStore: AgentRunStore
    private lateinit var inferenceProvider: AiInferenceProvider?
    
    override suspend fun initialize(context: EngineContext) {
        log.info("Initializing Halo Linux Node...")
        _state.value = EngineState.Initializing
        _health.value = EngineHealth.Recovering
        
        this.runtimeContext = context.runtime
        
        // Register core services
        context.registry.register(ServiceKey(EventBus::class), context.runtime.eventBus)
        context.registry.register(ServiceKey(ServiceRegistry::class), context.registry)
        
        // Initialize composition root
        initializeCompositionRoot(context)
        
        log.info("Halo Linux Node initialized")
        _health.value = EngineHealth.Healthy
        _state.value = EngineState.Running
    }
    
    private suspend fun initializeCompositionRoot(context: EngineContext) {
        // Determine data directories using XDG spec
        val home = Paths.get(System.getProperty("user.home"))
        val xdgDataHome = System.getenv("XDG_DATA_HOME")?.let { Paths.get(it) }
            ?: home.resolve(".local/share")
        val xdgConfigHome = System.getenv("XDG_CONFIG_HOME")?.let { Paths.get(it) }
            ?: home.resolve(".config")
        val xdgCacheHome = System.getenv("XDG_CACHE_HOME")?.let { Paths.get(it) }
            ?: home.resolve(".cache")
        
        val dataDir = xdgDataHome.resolve("halo-linux")
        val configDir = xdgConfigHome.resolve("halo-linux")
        val cacheDir = xdgCacheHome.resolve("halo-linux")
        
        Files.createDirectories(dataDir)
        Files.createDirectories(configDir)
        Files.createDirectories(cacheDir)
        
        // Initialize storage layers
        val storageConfig = StructuredStorageConfig(
            dataDir = dataDir.toString(),
            configDir = configDir.toString(),
            cacheDir = cacheDir.toString(),
        )
        
        structuredStorage = LinuxStorage()
        structuredStorage.initialize(storageConfig)
        
        // Initialize preferences storage at a fixed path
        preferencesStorage = SqliteRuntimePreferences()
        preferencesStorage.initialize(dataDir.resolve("preferences.db"))
        
        agentRunStore = SqliteAgentRunStore()
        agentRunStore.initialize(dataDir.resolve("agent_runs.db"))
        
        // Reconcile interrupted runs from previous crash
        reconcileInterruptedRuns()
        
        // Register services
        context.registry.register(ServiceKey(StructuredStorage::class), structuredStorage)
        context.registry.register(ServiceKey(Storage::class), preferencesStorage)
        context.registry.register(ServiceKey(AgentRunStore::class), agentRunStore)
    }
    
    private suspend fun reconcileInterruptedRuns() {
        var recovered = 0
        for (run in agentRunStore.nonTerminalRuns()) {
            // CAS ensures a concurrently completed run is not overwritten
            if (agentRunStore.claimResume(run.id, run.status)) {
                recovered++
                log.info("Recovered interrupted run: ${run.id.value} (was ${run.status})")
            }
        }
        if (recovered > 0) {
            log.info("Crash recovery: reconciled $recovered interrupted runs to INTERRUPTED state")
        }
    }
    
    private suspend fun initializeInferenceProvider() {
        // Check if provider is configured in preferences
        val providerId = preferencesStorage.getString("inference.provider.id")
        val baseUrl = preferencesStorage.getString("inference.provider.baseUrl")
        val authKindStr = preferencesStorage.getString("inference.provider.authKind", "NONE")
        
        if (providerId != null && baseUrl != null) {
            val authKind = ProviderAuthKind.valueOf(authKindStr)
            val config = LinuxProviderConfig(
                id = providerId,
                displayName = preferencesStorage.getString("inference.provider.displayName", "OpenAI Compatible"),
                baseUrl = baseUrl,
                authKind = authKind,
                protocol = AiWireProtocol.OPENAI_CHAT_COMPLETIONS,
            )
            
            val transport = OpenAiCompatibleTransport(
                client = OpenAiCompatibleTransport.defaultClient(),
                baseUrl = baseUrl,
                protocol = AiWireProtocol.OPENAI_CHAT_COMPLETIONS,
            )
            
            val credentialProvider = object : CredentialProvider {
                override fun hasCredential(): Boolean {
                    return preferencesStorage.getString("inference.credential.value") != null
                }
                override fun credential(): Credential? {
                    val value = preferencesStorage.getString("inference.credential.value")
                    val id = preferencesStorage.getString("inference.credential.id", "default.bearer")
                    return if (value != null) Credential(id, value) else null
                }
                override fun setCredential(credential: Credential) {
                    preferencesStorage.putString("inference.credential.id", credential.id)
                    preferencesStorage.putString("inference.credential.value", credential.value)
                }
                override fun clearCredential() {
                    preferencesStorage.remove("inference.credential.id")
                    preferencesStorage.remove("inference.credential.value")
                }
            }
            
            inferenceProvider = LinuxOpenAiCompatibleProvider(
                config = config,
                transport = transport,
                credentialProvider = credentialProvider,
            )
            
            log.info("Inference provider initialized: $providerId at $baseUrl")
        }
    }
    
    private suspend fun createAgentRun(interactionId: String): AgentRun {
        val nodeIdentity = structuredStorage.getNodeIdentity()
        return AgentRun.createNew(
            interactionId = interactionId,
            originNodeId = nodeIdentity.nodeId,
        )
    }
    
    override suspend fun start() {
        log.info("Starting Halo Linux Node...")
        _state.value = EngineState.Running
        _health.value = EngineHealth.Healthy
        
        // Initialize inference provider if configured
        initializeInferenceProvider()
        
        // Keep the application running
        scope.launch {
            while (_state.value != EngineState.Stopped && _state.value != EngineState.Disposed) {
                try {
                    delay(1000)
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
        
        // Close resources
        structuredStorage.close()
        agentRunStore.close()
    }
    
    // CLI command implementations
    suspend fun cmdDoctor(): String {
        val sb = StringBuilder()
        sb.appendLine("Halo Linux Node - Environment Diagnostics")
        sb.appendLine("==========================================")
        sb.appendLine()
        
        // Java/OS/Architecture
        sb.appendLine("Runtime:")
        sb.appendLine("  Java: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
        sb.appendLine("  OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
        sb.appendLine("  Arch: ${System.getProperty("os.arch")}")
        sb.appendLine()
        
        // Directories
        sb.appendLine("Directories:")
        sb.appendLine("  Data: ${structuredStorage.getDataDirectory()}")
        sb.appendLine("  Config: ${structuredStorage.getConfigDirectory()}")
        sb.appendLine("  Cache: ${structuredStorage.getCacheDirectory()}")
        sb.appendLine()
        
        // Database migration status
        sb.appendLine("Database:")
        try {
            val dbPath = Paths.get(structuredStorage.getDataDirectory()).resolve("halo.db")
            if (Files.exists(dbPath)) {
                sb.appendLine("  Main DB: OK (${dbPath.toAbsolutePath()})")
            } else {
                sb.appendLine("  Main DB: NOT FOUND")
            }
        } catch (e: Exception) {
            sb.appendLine("  Main DB: ERROR - ${e.message}")
        }
        sb.appendLine()
        
        // Audio (optional)
        sb.appendLine("Audio:")
        val audioDiagnostics = com.halo.adapters.audio.LinuxAudioDiagnostics()
        val audioResult = audioDiagnostics.enumerateDevices()
        sb.appendLine("  ALSA: ${if (audioResult.alsaAvailable) "Available" else "Not available"}")
        sb.appendLine("  PipeWire: ${if (audioResult.pipeWireAvailable) "Available" else "Not available"}")
        sb.appendLine("  Input devices: ${audioResult.inputDevices.size}")
        sb.appendLine("  Output devices: ${audioResult.outputDevices.size}")
        if (audioResult.errors.isNotEmpty()) {
            audioResult.errors.forEach { sb.appendLine("  Error: $it") }
        }
        sb.appendLine()
        
        // Provider reachability
        sb.appendLine("Inference Provider:")
        if (inferenceProvider != null) {
            val status = inferenceProvider!!.getStatus()
            sb.appendLine("  Status: ${status.state}")
            sb.appendLine("  Message: ${status.message ?: "N/A"}")
            if (status.readiness != ProviderReadiness.Unconfigured) {
                val connected = inferenceProvider!!.testConnection()
                sb.appendLine("  Connectivity: ${if (connected) "OK" else "FAILED"}")
            }
        } else {
            sb.appendLine("  Not configured")
        }
        sb.appendLine()
        
        // Agent run store status
        sb.appendLine("Agent Run Store:")
        val nonTerminal = agentRunStore.nonTerminalRuns()
        sb.appendLine("  Non-terminal runs: ${nonTerminal.size}")
        if (nonTerminal.isNotEmpty()) {
            nonTerminal.forEach { run ->
                sb.appendLine("    ${run.id.value}: ${run.status} (revision ${run.revision})")
            }
        }
        
        return sb.toString()
    }
    
    suspend fun cmdStatus(): String {
        val sb = StringBuilder()
        sb.appendLine("Halo Linux Node Status")
        sb.appendLine("=====================")
        sb.appendLine()
        sb.appendLine("Engine: ${_state.value}")
        sb.appendLine("Health: ${_health.value}")
        
        if (inferenceProvider != null) {
            val status = inferenceProvider!!.getStatus()
            sb.appendLine("Provider: ${status.state} - ${status.message ?: "ready"}")
        } else {
            sb.appendLine("Provider: unconfigured")
        }
        
        val nonTerminal = agentRunStore.nonTerminalRuns()
        sb.appendLine("Active runs: ${nonTerminal.size}")
        
        return sb.toString()
    }
    
    suspend fun cmdAsk(query: String): String {
        if (inferenceProvider == null) {
            return "Error: No inference provider configured. Use 'config set-provider' to configure one."
        }
        
        val run = createAgentRun(UUID.randomUUID().toString())
        agentRunStore.create(run)
        
        val request = AiRequest(
            text = query,
            modelId = preferencesStorage.getString("inference.provider.modelId"),
        )
        val context = AiContext.empty()
        
        try {
            agentRunStore.update(run.copy(status = AgentRunStatus.RUNNING, revision = 1, updatedAt = Instant.now()))
            
            val response = inferenceProvider!!.generate(request, context)
            
            agentRunStore.update(run.copy(
                status = AgentRunStatus.COMPLETED,
                revision = 2,
                updatedAt = Instant.now(),
                checkpointRef = response.text,
            ))
            
            return response.text
        } catch (e: Exception) {
            agentRunStore.update(run.copy(
                status = AgentRunStatus.FAILED,
                revision = 2,
                updatedAt = Instant.now(),
                failureCategory = e::class.java.simpleName,
            ))
            return "Error: ${e.message}"
        }
    }
    
    suspend fun cmdConfigShow(): String {
        val sb = StringBuilder()
        sb.appendLine("Halo Linux Node Configuration")
        sb.appendLine("=============================")
        sb.appendLine()
        
        val providerId = preferencesStorage.getString("inference.provider.id")
        if (providerId != null) {
            sb.appendLine("Inference Provider:")
            sb.appendLine("  ID: $providerId")
            sb.appendLine("  Name: ${preferencesStorage.getString("inference.provider.displayName")}")
            sb.appendLine("  Base URL: ${preferencesStorage.getString("inference.provider.baseUrl")}")
            sb.appendLine("  Auth: ${preferencesStorage.getString("inference.provider.authKind", "NONE")}")
            sb.appendLine("  Model: ${preferencesStorage.getString("inference.provider.modelId", "auto")}")
        } else {
            sb.appendLine("Inference Provider: not configured")
        }
        
        return sb.toString()
    }
}

fun main(args: Array<String>) {
    val app = HaloNodeApplication()
    
    // Simple CLI handling
    runBlocking {
        when (args.firstOrNull()) {
            "--version" -> println("halo-node 0.1.0-SNAPSHOT (commit: unknown)")
            "doctor" -> println(app.cmdDoctor())
            "status" -> println(app.cmdStatus())
            "start" -> {
                println("Starting Halo Linux Node...")
                app.initialize(com.halo.sdk.runtime.EngineContext(
                    runtime = com.halo.sdk.runtime.RuntimeContext(
                        eventBus = com.halo.sdk.runtime.DefaultEventBus(),
                        commandBus = com.halo.sdk.runtime.DefaultCommandBus(),
                        queryBus = com.halo.sdk.runtime.DefaultQueryBus(),
                        scheduler = com.halo.sdk.runtime.DefaultRuntimeScheduler(),
                        registry = com.halo.sdk.runtime.DefaultServiceRegistry(),
                        logger = com.halo.sdk.runtime.DefaultRuntimeLogger(),
                    ),
                    registry = com.halo.sdk.runtime.DefaultServiceRegistry(),
                ))
                app.start()
            }
            "ask" -> {
                val query = args.drop(1).joinToString(" ")
                if (query.isBlank()) {
                    println("Usage: halo-node ask <query>")
                } else {
                    println(app.cmdAsk(query))
                }
            }
            "config" -> {
                when (args.getOrNull(1)) {
                    "show" -> println(app.cmdConfigShow())
                    else -> println("Usage: halo-node config show")
                }
            }
            else -> println("Usage: halo-node [--version|doctor|status|start|ask <query>|config show]")
        }
    }
}