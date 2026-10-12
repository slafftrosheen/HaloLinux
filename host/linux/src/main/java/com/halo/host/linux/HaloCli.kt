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
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** Local-only CLI and daemon control interface. No LAN listener is created. */
object HaloCli {
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
                val question = args.drop(1).joinToString(" ").trim()
                if (question.isEmpty()) throw IllegalArgumentException("Provide a question")
                val reply = request(JSONObject().put("op", "ask").put("text", question))
                println(reply.getString("text")); 0
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
                System.err.println("Usage: halo-node [--version|doctor|config show|start|status|ask <text>|memory add <text>|memory search <query>|memory delete <id>|storage check|storage backup <absolute-path>]")
                2
            }
        }
    } catch (e: IllegalArgumentException) {
        System.err.println("Configuration/request error: ${e.message}"); 2
    } catch (e: Exception) {
        // Never print exception stack traces that could include secret-bearing URLs.
        System.err.println("Halo node error: ${e.javaClass.simpleName}. Check configuration and daemon status."); 1
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
        runBlocking {
            storage.initialize(LinuxStorageConfig(dataDir = config.dataDir))
            storage.getNodeIdentity()
        }
        val runtime = LinuxRuntimeBridge()
        val server = LocalControlServer(socketPath()) { input ->
            runBlocking { handle(storage, config, runtime, input) }
        }
        val shutdown = Thread { server.close() }
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            runBlocking { runtime.start() }
            println("Halo node runtime initialized (local socket only; no LAN listener)")
            server.serve()
        } finally {
            server.close()
            runBlocking {
                try { runtime.stop() } finally { storage.close() }
            }
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) }
        }
    }

    private suspend fun handle(store: LinuxStorage, config: NodeConfig, runtime: LinuxRuntimeBridge, input: JSONObject): JSONObject {
        val op = input.optString("op")
        return when (op) {
            "status" -> JSONObject().put("ok", true)
                 .put("process", "RUNNING")
                .put("runtime", runtime.engine.state.value.javaClass.simpleName)
                .put("runtime_health", runtime.engine.health.value.name)
                .put("host", LinuxSystemProbe.snapshot().let { system ->
                    JSONObject().put("architecture", system.architecture)
                        .put("memory_total_bytes", system.memoryTotalBytes ?: JSONObject.NULL)
                        .put("memory_available_bytes", system.memoryAvailableBytes ?: JSONObject.NULL)
                        .put("soc_temperature_c", system.socTemperatureC ?: JSONObject.NULL)
                        .put("thermal_throttling", "NOT_CHECKED")
                })
                .put("storage", "READY")
                .put("inference_configured", config.providerConfigured)
                .put("provider_connectivity", "NOT_CHECKED")
                .put("selected_model", config.providerModel ?: JSONObject.NULL)
                .put("audio", "NOT_VERIFIED")
                .put("sync", "DISABLED")
                .put("node_id", store.getNodeIdentity().nodeId)
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
                val hits = store.searchMemories(text, 20)
                val results = JSONArray()
                hits.forEach { results.put(JSONObject().put("id", it.id).put("content", it.content)) }
                JSONObject().put("ok", true).put("results", results)
            }
            "ask" -> {
                require(config.providerConfigured) { "Configure endpoint and model in config.yaml" }
                val text = input.getString("text").trim()
                require(text.isNotBlank() && text.length <= 32_000) { "Invalid question" }
                val endpoint = config.providerEndpoint!!
                val auth = config.apiKey
                val uri = java.net.URI(endpoint)
                val insecureLoopback = uri.scheme == "http" &&
                    uri.host.lowercase() in setOf("localhost", "127.0.0.1", "::1")
                val credentials = object : CredentialProvider {
                    private var secret: Credential? = if (auth == null)
                        Credential("local.none", "") else Credential("remote.bearer", auth)
                    override fun hasCredential(): Boolean = secret != null
                    override fun credential(): Credential? = secret
                    override fun setCredential(credential: Credential) { secret = credential }
                    override fun clearCredential() { secret = null }
                }
                val transport = OpenAiCompatibleTransport(
                    OkHttpClient.Builder()
                        .callTimeout(120, TimeUnit.SECONDS)
                        .build(),
                    endpoint,
                    AiWireProtocol.OPENAI_CHAT_COMPLETIONS,
                )
                val provider = LinuxOpenAiCompatibleProvider(
                    LinuxProviderConfig(
                        baseUrl = endpoint,
                        executionLocation = endpointExecutionLocation(endpoint),
                        authKind = if (auth == null) ProviderAuthKind.NONE else ProviderAuthKind.BEARER_TOKEN,
                        allowInsecureLocalCredentialTransport = insecureLoopback,
                    ),
                    transport,
                    credentials,
                )
                val start = System.nanoTime()
                val response = provider.generate(AiRequest(text = text, modelId = config.providerModel), AiContext())
                require(response.text.isNotBlank()) { "Provider returned an empty response" }
                val now = Instant.now()
                val conversationId = UUID.randomUUID().toString()
                val durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                store.storeConversation(ConversationRecord(
                    id = conversationId, title = text.take(80),
                    createdAt = now, updatedAt = now,
                    providerId = provider.id, modelId = config.providerModel, metadata = "{}",
                    messages = listOf(
                        MessageRecord(UUID.randomUUID().toString(), conversationId, "user", text,
                            null, null, 0L, 0L, now, "{}"),
                        MessageRecord(UUID.randomUUID().toString(), conversationId, "assistant", response.text,
                            provider.id, config.providerModel, 0L, durationMs, Instant.now(), "{}"),
                    ),
                ))
                JSONObject().put("ok", true).put("text", response.text)
                    .put("conversation_id", conversationId).put("latency_ms", durationMs)
            }
            else -> JSONObject().put("ok", false).put("error", "Unsupported operation")
        }
    }
}
