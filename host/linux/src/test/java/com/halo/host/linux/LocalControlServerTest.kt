package com.halo.host.linux

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LocalControlServerTest {
    @TempDir lateinit var tmp: Path

    private fun start(path: Path): Pair<LocalControlServer, Thread> {
        val server = LocalControlServer(path) { request ->
            JSONObject().put("ok", true).put("echo", request.optString("text", "ready"))
        }
        val runner = Thread { server.serve() }
        runner.start()
        repeat(100) {
            if (Files.exists(path)) return server to runner
            Thread.sleep(10)
        }
        throw AssertionError("Socket did not appear")
    }

    @Test fun requestResponseAndShutdown() {
        val path = tmp.resolve("node.sock")
        val (server, runner) = start(path)
        try {
            val response = LocalControlClient.exchange(path, JSONObject().put("text", "hello"))
            assertEquals("hello", response.getString("echo"))
        } finally {
            server.close()
            runner.join(2000)
        }
        assertFalse(runner.isAlive)
    }

    @Test fun idleClientDoesNotBlockAnotherClient() {
        val path = tmp.resolve("node.sock")
        val (server, runner) = start(path)
        try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { stalled ->
                stalled.connect(UnixDomainSocketAddress.of(path))
                val startedAt = System.nanoTime()
                val result = LocalControlClient.exchange(path, JSONObject().put("text", "status"))
                assertEquals("status", result.getString("echo"))
                assertTrue((System.nanoTime() - startedAt) / 1_000_000 < 2000)
            }
        } finally { server.close(); runner.join(2000) }
    }

    @Test fun rejectsOversizedFrame() {
        val bytes = (("x".repeat(LocalControlServer.MAX_REQUEST_BYTES + 2)) + "\n").toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            LocalControlServer.readFrame(bytes.inputStream())
        }
    }
    @Test fun duplicateInstanceCannotAcquireLockOrRemoveExistingSocket() {
        val path = tmp.resolve("locked.sock")
        val owner = LocalControlServer(path) {
            JSONObject().put("ok", true)
        }
        val contender = LocalControlServer(path) {
            JSONObject().put("ok", true)
        }
        try {
            owner.acquireInstanceLock()
            assertThrows(IllegalStateException::class.java) {
                contender.acquireInstanceLock()
            }
            // No socket was opened, and the lock remains owned by the first server.
            assertFalse(Files.exists(path))
            assertDoesNotThrow { owner.acquireInstanceLock() }
        } finally {
            contender.close()
            owner.close()
        }
    }

    @Test fun providerErrorsAreActionableWithoutLeakingExceptionContents() {
        val secret = "Bearer super-private-token"
        val response = LocalControlErrors.safeResponse(
            com.halo.sdk.provider.AiProviderException(
                com.halo.sdk.provider.AiErrorType.AUTHENTICATION_FAILED, secret
            )
        )
        assertEquals("AUTHENTICATION_FAILED", response.getString("error_code"))
        assertFalse(response.toString().contains(secret))
    }

    @Test fun unknownExceptionsDoNotLeakPrivateContent() {
        val response = LocalControlErrors.safeResponse(
            IllegalStateException("user private memory and path /home/example"))
        assertEquals("REQUEST_FAILED", response.getString("error_code"))
        assertFalse(response.toString().contains("/home/example"))
    }

    @Test fun oversizedResponseReturnsBoundedProtocolError() {
        val path = tmp.resolve("oversized.sock")
        val server = LocalControlServer(path) {
            JSONObject().put("ok", true)
                .put("content", "large-reply-".repeat(LocalControlServer.MAX_REQUEST_BYTES / 8))
        }
        val runner = Thread { runCatching { server.serve() } }
        runner.start()
        try {
            repeat(100) {
                if (!Files.exists(path)) Thread.sleep(10)
            }
            assertTrue(Files.exists(path))
            val failure = assertThrows(LocalControlFailure::class.java) {
                LocalControlClient.exchange(path, JSONObject().put("op", "status"))
            }
            assertEquals("RESPONSE_TOO_LARGE", failure.code)
        } finally {
            server.close()
            runner.join(2000)
        }
        assertFalse(runner.isAlive)
    }

    @Test fun rejectsInvalidUtf8RatherThanReplacingBytes() {
        val malformed = byteArrayOf(0xC3.toByte(), 0x28, 0x0A)
        assertThrows(IllegalArgumentException::class.java) {
            LocalControlServer.readFrame(malformed.inputStream())
        }
    }

    @Test fun oversizedClientRequestIsRejectedBeforeWritingSocketData() {
        val path = tmp.resolve("client-limit.sock")
        val (server, runner) = start(path)
        try {
            val failure = assertThrows(LocalControlFailure::class.java) {
                LocalControlClient.exchange(
                    path, JSONObject().put("text", "large".repeat(40_000))
                )
            }
            assertEquals("REQUEST_TOO_LARGE", failure.code)
        } finally { server.close(); runner.join(2000) }
    }

}
