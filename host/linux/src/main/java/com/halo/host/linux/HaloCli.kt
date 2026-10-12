package com.halo.host.linux

import com.halo.adapters.audio.LinuxAudioDiagnostics
import com.halo.adapters.inference.LinuxOpenAiCompatibleProvider
import com.halo.adapters.inference.LinuxProviderConfig
import com.halo.adapters.storage.ConversationRecord
import com.halo.adapters.storage.LinuxStorage
import com.halo.adapters.storage.LinuxStorageConfig
import com.halo.adapters.storage.MemoryRecord
import com.halo.adapters.storage.MessageRecord
import com.halo.sdk.provider.AiContext
import com.halo.sdk.provider.AiExecutionLocation
import com.halo.sdk.provider.AiRequest
import com.halo.sdk.provider.AiWireProtocol
import com.halo.sdk.provider.Credential
import com.halo.sdk.provider.CredentialProvider
import com.halo.sdk.provider.OpenAiCompatibleTransport
import com.halo.sdk.provider.ProviderAuthKind
import com.halo.sdk.provider.endpointExecutionLocation
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** Local-only CLI and daemon control interface. No LAN listener is created. */
object HaloCli {
    private val mutationGate = Mutex()
    private fun socketPath(): Path {
        val home = Path.of(System.getProperty("user.home"))
        val runtime = System.getenv("XDG_RUNTIME_DIR")?.let(Path::of)
            ?: home.resolve(".local/state/halo-linux/run")
        Files.createDirectories(runtime)
        val dir = runtime.resolve("halo-linux")
        Files.createDirectories(dir)
        Files.setPosixFilePermissions(dir, setOf(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
        ))
        return dir.resolve("node.sock")
    }

    fun run(args: Array<String>): Int = try {
        when (args.firstOrNull()) {
            "--version" -> { println("halo-node 0.1.0-dev"); 0 }
            "config" -> {
                if (args.getOrNull(1) != "show") {
                    System.err.println("Usage: halo-node config show"); 2
                } else {
                    val c = NodeConfiguration.load()
                    println(JSONObject().put("name", c.displayName)
                        .put("config_path", c.configPath ?: JSONObject.NULL)
                        .put("endpoint", c.providerEndpoint ?: JSONObject.NULL)
                        .put("model", c.providerModel ?: JSONObject.NULL)
                        .put("credential_configured", c.apiKey != null)
                        .put("audio_enabled", c.audioEnabled).toString(2))
                    0
                }
            }
            "doctor" -> { doctor(); 0 }
            "start" -> { serve(); 0 }
            "status" -> {
                println(request(JSONObject().put("op", "status")).toString(2)); 0
            }
            "ask" -> {
                val question = questionFromArguments(args.drop(1))
                require(question.isNotEmpty()) { "Provide a question" }
                val reply = request(JSONObject().put("op", "ask").put("text", question))
                println(reply.getString("text"))
                if (reply.optString("canonical_journal_state") == "NEEDS_RECONCILIATION") {
                    System.err.println(
                        "WARNING: Answer saved locally; canonical agent journal needs reconciliation. " +
                        "Run: halo-node agent recovery. Do not retry automatically."
                    )
                }
                0
            }
            "node" -> {
                if (args.getOrNull(1) != "backup" || args.size != 3) {
                    throw IllegalArgumentException("Usage: halo-node node backup /absolute/private/snapshot-folder")
                }
                println(request(JSONObject().put("op", "node.backup")
                    .put("destination", args[2])).toString(2))
                0
            }
            "agent" -> {
                when (args.getOrNull(1)) {
                    "recovery" -> {
                        require(args.size == 2) { "Usage: halo-node agent recovery" }
                        val snapshot = request(JSONObject().put("op", "status"))
                            .getJSONObject("canonical_agent_recovery")
                        println(snapshot.toString(2))
                        0
                    }
                    "backup" -> {
                        require(args.size == 3) { "Usage: halo-node agent backup /absolute/private/folder/agent.db" }
                        println(request(JSONObject().put("op", "agent.backup")
                            .put("destination", args[2])).toString(2))
                        0
                    }
                    else -> throw IllegalArgumentException("Usage: halo-node agent [recovery|backup <absolute-path>]")
                }
            }
            "provider" -> {
                if (args.getOrNull(1) != "check") throw IllegalArgumentException("Usage: halo-node provider check")
                println(request(JSONObject().put("op", "provider.check")).toString(2)); 0
            }
            "storage" -> {
                val op = args.getOrNull(1)
                when (op) {
                    "check" -> {
                        println(request(JSONObject().put("op", "storage.check")).toString(2))
                        0
                    }
                    "backup" -> {
                        val destination = args.getOrNull(2)?.trim()
                        if (destination.isNullOrEmpty() || args.size != 3) {
                            throw IllegalArgumentException("Usage: halo-node storage backup /absolute/path/backup.db")
                        }
                        println(request(JSONObject().put("op", "storage.backup")
                            .put("destination", destination)).toString(2))
                        0
                    }
                    else -> throw IllegalArgumentException("Usage: halo-node storage [check|backup <absolute-path>]")
                }
            }
            "memory" -> {
                val op = args.getOrNull(1)
                val value = args.drop(2).joinToString(" ").trim()
                if (op !in setOf("add", "search", "delete") || value.isBlank()) {
                    throw IllegalArgumentException("Usage: halo-node memory [add|search|delete] <text-or-id>")
                }
                val reply = request(JSONObject().put("op", "memory.$op").put("text", value))
                println(reply.toString(2)); 0
            }
            else -> {
                System.err.println("Usage: halo-node [--version|doctor|config show|start|status|ask <text-or->|memory add <text>|memory search <query>|memory delete <id>|agent recovery|node backup <absolute-private-folder>|storage check|storage backup <absolute-path>]")
                2
            }
        }
    } catch (e: LocalControlFailure) {
        System.err.println("Halo ${e.code}: ${e.message}")
        1
    } catch (e: IllegalArgumentException) {
        System.err.println("Configuration/request error: ${e.message}"); 2
    } catch (e: Exception) {
        // Never print exception stack traces that could include secret-bearing URLs.
        System.err.println("Halo node error: ${e.javaClass.simpleName}. Check configuration and daemon status."); 1
    }

    /** Use 'halo-node ask -' to avoid exposing private prompts in shell argv. */
    internal fun questionFromArguments(
        values: List<String>, input: java.io.InputStream = System.`in`,
    ): String {
        val raw = if (values.size == 1 && values[0] == "-") {
            val bytes = input.readNBytes(96 * 1024 + 1)
            require(bytes.size <= 96 * 1024) { "Question exceeds stdin limit" }
            bytes.toString(Charsets.UTF_8)
        } else {
            values.joinToString(" ")
        }
        val text = raw.trim()
        require(text.length <= 32_000) { "Question exceeds 32,000 characters" }
        require(org.json.JSONObject().put("text", text)
            .toString().toByteArray(Charsets.UTF_8).size < 120 * 1024) {
            "Question exceeds local request limit"
        }
        return text
    }

    private fun doctor() {
        val c = NodeConfiguration.load()
        val audio = runBlocking { LinuxAudioDiagnostics().enumerateDevices() }
        println("HaloLinux environment")
        println("Java: ${System.getProperty("java.version")}")
        val system = LinuxSystemProbe.snapshot()
        println("Architecture: ${system.architecture}")
        println("Memory total: ${system.memoryTotalBytes?.toString() ?: "UNKNOWN"} bytes")
        println("Memory available: ${system.memoryAvailableBytes?.toString() ?: "UNKNOWN"} bytes")
        println("SoC thermal sensor: ${system.socTemperatureC?.toString() ?: "UNKNOWN"} °C")
        println("Throttle/power margin: NOT CHECKED")
        println("Configuration: ${c.configPath ?: "not found (defaults used)"}")
        println("Inference configured: ${c.providerConfigured}; connectivity: NOT CHECKED")
        println("ALSA detected: ${audio.alsaAvailable}; PipeWire detected: ${audio.pipeWireAvailable}")
        println("Audio capture/playback verified: NO")
        println("Synchronization: DISABLED")
    }

    private fun request(message: JSONObject): JSONObject =
        LocalControlClient.exchange(socketPath(), message)

    private fun serve() {
        val config = NodeConfiguration.load()
        val storage = LinuxStorage()
        val runtime = LinuxRuntimeBridge(storage)
        val provider = LinuxProviderLifecycle.create(config)
        var recovery: LinuxRecoverySupport = DisabledRecoverySupport
        val cognition = LinuxCognitiveTurn(provider, storage, runtime, recoverySupport = { recovery })
        val server = LocalControlServer(socketPath()) { input ->
            runBlocking { handle(storage, config, runtime, provider, cognition, recovery, input) }
        }
        val shutdown = Thread { server.close() }
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            // Own the process before touching persistent state. Only the lock owner
            // may run checkpoint recovery or load node identity.
            server.acquireInstanceLock()
            runBlocking {
                storage.initialize(LinuxStorageConfig(dataDir = config.dataDir))
                storage.getNodeIdentity()
                storage.reconcileInterruptedTurns()
                recovery = LinuxRecoverySupportFactory.open(
                    java.nio.file.Path.of(storage.getDataDirectory())
                )
                // The canonical coordinator classifies interrupted runs once,
                // without resuming providers or executing tools.
                recovery.prepare()
                recovery.inspect()
                runtime.start()
            }
            println("Halo node runtime initialized (local socket only; no LAN listener)")
            server.serve()
        } finally {
            server.close()
            runBlocking {
                try { runtime.stop() } finally {
                    try { provider.close() } finally {
                        try { recovery.close() } finally { storage.close() }
                    }
                }
            }
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
        }
    }

    private suspend fun handle(store: LinuxStorage, config: NodeConfig, runtime: LinuxRuntimeBridge, provider: LinuxProviderLifecycle, cognition: LinuxCognitiveTurn, recovery: LinuxRecoverySupport, input: JSONObject): JSONObject {
        val protocolVersion = input.optInt("protocol_version", -1)
        if (protocolVersion != 1) {
            return JSONObject().put("ok", false)
                .put("error", "Unsupported local control protocol version")
                .put("supported_version", 1)
        }
        val op = input.optString("op")
        // Serializes all host-controlled writes, including inference turns.
        // Read-only status and provider health remain concurrently available.
        val writeOperations = setOf(
            "ask", "memory.add", "memory.delete", "storage.backup",
            "agent.backup", "node.backup"
        )
        return if (op in writeOperations) {
            mutationGate.withLock { dispatch(op, store, config, runtime, provider, cognition, recovery, input) }
        } else {
            dispatch(op, store, config, runtime, provider, cognition, recovery, input)
        }
    }

    private suspend fun dispatch(
        op: String, store: LinuxStorage, config: NodeConfig, runtime: LinuxRuntimeBridge,
        provider: LinuxProviderLifecycle, cognition: LinuxCognitiveTurn,
        recovery: LinuxRecoverySupport, input: JSONObject,
    ): JSONObject = when (op) {
            "status" -> JSONObject().put("ok", true)
                 .put("process", "RUNNING")
                .put("runtime", runtime.engine.state.value.javaClass.simpleName)
                .put("runtime_health", runtime.engine.health.value.name)
                .put("control_protocol_version", 1)
                .put("host", LinuxSystemProbe.snapshot().let { system ->
                    JSONObject().put("architecture", system.architecture)
                        .put("memory_total_bytes", system.memoryTotalBytes ?: JSONObject.NULL)
                        .put("memory_available_bytes", system.memoryAvailableBytes ?: JSONObject.NULL)
                        .put("soc_temperature_c", system.socTemperatureC ?: JSONObject.NULL)
                        .put("thermal_throttling", "NOT_CHECKED")
                })
                .put("storage", "READY")
                .put("interrupted_text_turns", runtime.query(CountInterruptedTextTurns))
                .put("canonical_agent_recovery", recovery.inspect())
                .put("inference_configured", config.providerConfigured)
                .put("provider", provider.snapshot().let { snapshot ->
                    JSONObject().put("phase", snapshot.phase.name)
                        .put("provider_id", snapshot.providerId ?: JSONObject.NULL)
                        .put("model_id", snapshot.modelId ?: JSONObject.NULL)
                        .put("attempts", snapshot.attempts)
                        .put("successes", snapshot.successes)
                        .put("in_flight", snapshot.inFlight)
                        .put("last_failure_type", snapshot.lastFailureType ?: JSONObject.NULL)
                        .put("last_latency_ms", snapshot.lastLatencyMs ?: JSONObject.NULL)
                })
                .put("selected_model", config.providerModel ?: JSONObject.NULL)
                .put("audio", "NOT_VERIFIED")
                .put("sync", "DISABLED")
                .put("node_id", store.getNodeIdentity().nodeId)
            "node.backup" -> {
                LinuxNodeSnapshot.create(
                    java.nio.file.Path.of(input.getString("destination")), store, recovery
                )
            }
            "agent.backup" -> {
                val destination = java.nio.file.Path.of(input.getString("destination"))
                recovery.backup(destination)
            }
            "provider.check" -> {
                val connected = provider.checkConnectivity()
                JSONObject().put("ok", true).put("connection_verified", connected)
                    .put("phase", provider.snapshot().phase.name)
            }
            "storage.check" -> {
                val report = store.checkDatabase()
                JSONObject().put("ok", report.integrityOk)
                    .put("integrity", report.integrityMessage)
                    .put("schema_version", report.schemaVersion)
                    .put("memory_count", report.memoryCount)
                    .put("conversation_count", report.conversationCount)
                    .put("database_bytes", report.databaseBytes)
            }
            "storage.backup" -> {
                val path = java.nio.file.Path.of(input.getString("destination"))
                val receipt = store.createBackup(path)
                JSONObject().put("ok", true)
                    .put("backup_path", receipt.path)
                    .put("bytes", receipt.bytes)
                    .put("contains_private_identity", true)
            }
            "memory.add" -> {
                val text = input.getString("text").trim()
                require(text.isNotBlank() && text.length <= 100_000) { "Invalid memory text" }
                val now = Instant.now()
                val id = UUID.randomUUID().toString()
                store.storeMemory(MemoryRecord(
                    id, text, "explicit-cli", "personal", 1.0, null, now, now, "{}"
                ))
                JSONObject().put("ok", true).put("id", id)
            }
            "memory.delete" -> {
                val id = input.getString("text").trim()
                JSONObject().put("ok", true).put("deleted", store.deleteMemory(id))
            }
            "memory.search" -> {
                val text = input.getString("text").trim()
                require(text.isNotBlank() && text.length <= 1000) { "Invalid search query" }
                val hits = runtime.query(SearchLocalMemory(text, 20))
                LinuxMemoryResponses.searchResults(hits)
            }
            "ask" -> {
                val text = input.getString("text").trim()
                val answer = cognition.ask(text)
                JSONObject().put("ok", true).put("text", answer.text)
                    .put("conversation_id", answer.conversationId)
                    .put("interaction_id", answer.interactionId)
                    .put("agent_run_id", answer.agentRunId ?: JSONObject.NULL)
                    .put("canonical_journal_state", answer.canonicalJournalState)
                    .put("provider_id", answer.providerId)
                    .put("model_id", answer.modelId ?: JSONObject.NULL)
                    .put("latency_ms", answer.latencyMs)
            }
            else -> JSONObject().put("ok", false).put("error", "Unsupported operation")
        }
}