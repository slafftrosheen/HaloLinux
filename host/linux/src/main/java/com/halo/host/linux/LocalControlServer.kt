package com.halo.host.linux

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/**
 * Local-only control protocol. Each connection receives one JSON-line request.
 * A bounded worker pool ensures a stalled client cannot block process health.
 *
 * The lock is held for the entire daemon lifetime; stale socket removal happens
 * only after the lock is acquired. Never unlink an active daemon's socket.
 */
/** Non-sensitive client failure intended to be shown by the CLI. */
class LocalControlFailure(val code: String, message: String) : RuntimeException(message)

internal object LocalControlErrors {
    fun safeResponse(error: Exception): JSONObject {
        val code: String
        val safeText: String
        when (error) {
            is com.halo.sdk.provider.AiProviderException -> {
                code = error.type.name
                safeText = when (error.type) {
                    com.halo.sdk.provider.AiErrorType.PROVIDER_UNAVAILABLE ->
                        "No working inference provider is configured or available."
                    com.halo.sdk.provider.AiErrorType.MODEL_NOT_SELECTED,
                    com.halo.sdk.provider.AiErrorType.MODEL_NOT_FOUND ->
                        "Select an installed model in config.yaml."
                    com.halo.sdk.provider.AiErrorType.AUTHENTICATION_FAILED ->
                        "Provider authentication failed. Check your private credentials."
                    com.halo.sdk.provider.AiErrorType.NETWORK_UNAVAILABLE,
                    com.halo.sdk.provider.AiErrorType.NETWORK_ERROR ->
                        "Inference endpoint is unreachable."
                    com.halo.sdk.provider.AiErrorType.TIMEOUT ->
                        "The inference request timed out."
                    com.halo.sdk.provider.AiErrorType.RATE_LIMITED ->
                        "Inference is busy or the provider is rate-limiting. Try later."
                    com.halo.sdk.provider.AiErrorType.UNSUPPORTED_CAPABILITY ->
                        "This Linux runtime cannot execute the requested capability."
                    else -> "Inference request failed. Check provider settings and diagnostics."
                }
            }
            is IllegalArgumentException -> {
                code = "INVALID_REQUEST"
                safeText = "Invalid request or configuration value."
            }
            else -> {
                code = "REQUEST_FAILED"
                safeText = "The local operation failed. Check sanitized daemon logs."
            }
        }
        // Never reflect arbitrary exception text (URLs, credentials, prompts, file paths).
        return JSONObject().put("ok", false).put("error_code", code).put("error", safeText)
    }
}

class LocalControlServer(
    private val path: Path,
    private val handler: (JSONObject) -> JSONObject,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val clients = ThreadPoolExecutor(
        4, 4, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16)
    ) { task -> Thread(task, "halo-local-request").apply { isDaemon = true } }
    private val deadlines: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor {
        task -> Thread(task, "halo-local-deadlines").apply { isDaemon = true }
    }
    private var server: ServerSocketChannel? = null
    private var lockChannel: FileChannel? = null
    private var lock: java.nio.channels.FileLock? = null

    /**
     * Call before opening SQLite or performing startup recovery. A second process
     * must never mutate an active daemon's checkpoints during attempted startup.
     */
    @Synchronized
    fun acquireInstanceLock() {
        check(!closed.get()) { "Control server was closed" }
        if (lock != null) return
        val parent = path.parent ?: error("Socket requires parent directory")
        Files.createDirectories(parent)
        require(!Files.isSymbolicLink(parent)) { "Socket directory must not be a symlink" }
        Files.setPosixFilePermissions(parent, setOf(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
        ))
        lockChannel = FileChannel.open(
            parent.resolve("node.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE
        )
        lock = try {
            lockChannel!!.tryLock()
        } catch (_: java.nio.channels.OverlappingFileLockException) { null }
        if (lock == null) {
            lockChannel?.close()
            lockChannel = null
            error("Halo node is already running")
        }
    }

    fun serve() {
        acquireInstanceLock()
        try {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(path)) { "Refusing symlinked control socket" }
                Files.delete(path)
            }
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            server!!.bind(UnixDomainSocketAddress.of(path))
            Files.setPosixFilePermissions(path, setOf(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE
            ))
            while (!closed.get()) {
                val client = try { server!!.accept() }
                catch (_: java.nio.channels.ClosedChannelException) { break }
                try {
                    clients.execute { serveClient(client) }
                } catch (_: RejectedExecutionException) {
                    client.close() // overloaded; fail closed rather than blocking accept
                }
            }
        } finally {
            close()
        }
    }

    private fun serveClient(client: SocketChannel) {
        val watchdog = deadlines.schedule(
            { runCatching { client.close() } }, 180, TimeUnit.SECONDS
        )
        try {
            client.use {
                val input = Channels.newInputStream(it).buffered(8192)
                val line = readFrame(input)
                val response = try {
                    handler(JSONObject(line))
                } catch (failure: Exception) {
                    LocalControlErrors.safeResponse(failure)
                }
                Channels.newOutputStream(it).write((response.toString() + "\n").toByteArray(Charsets.UTF_8))
            }
        } catch (_: Exception) {
            // A malformed/stalled client must not terminate the daemon.
        } finally {
            watchdog.cancel(false)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server?.close() }
        clients.shutdownNow()
        deadlines.shutdownNow()
        if (lock != null) {
            // Only the owner of the lock may remove the named socket.
            runCatching { Files.deleteIfExists(path) }
        }
        runCatching { lock?.release() }
        runCatching { lockChannel?.close() }
    }

    companion object {
        const val MAX_REQUEST_BYTES = 131_072

        internal fun readFrame(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (bytes.size() <= MAX_REQUEST_BYTES) {
                val value = input.read()
                if (value < 0) throw IllegalArgumentException("Incomplete control frame")
                if (value == 10) return bytes.toString(Charsets.UTF_8)
                bytes.write(value)
            }
            throw IllegalArgumentException("Control frame exceeds maximum size")
        }
    }
}

/** CLI caller: private Unix socket, single bounded JSON-line request. */
object LocalControlClient {
    fun exchange(path: Path, input: JSONObject): JSONObject {
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            // A separate deadline shuts down a wedged daemon response.
            val timeout = Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "halo-client-timeout").apply { isDaemon = true }
            }
            val cancellation = timeout.schedule(
                { runCatching { channel.close() } }, 180, TimeUnit.SECONDS
            )
            try {
                channel.connect(UnixDomainSocketAddress.of(path))
                Channels.newOutputStream(channel).write(
                    (JSONObject(input.toString()).put("protocol_version", 1).toString() + "\n")
                        .toByteArray(Charsets.UTF_8)
                )
                val response = LocalControlServer.readFrame(Channels.newInputStream(channel).buffered(8192))
                val result = JSONObject(response)
                if (!result.optBoolean("ok")) {
                    throw LocalControlFailure(
                        result.optString("error_code", "REQUEST_REJECTED"),
                        result.optString("error", "Request rejected")
                    )
                }
                return result
            } finally {
                cancellation.cancel(false)
                timeout.shutdownNow()
            }
        }
    }
}
