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
}
