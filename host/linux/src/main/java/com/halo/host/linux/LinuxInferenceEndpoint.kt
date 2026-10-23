package com.halo.host.linux

import java.net.InetAddress
import java.net.URI
import java.util.Locale

/** A configured destination, not a grant to disclose content off this device. */
class LinuxInferenceEndpoint private constructor(
    val host: String,
    val isLoopback: Boolean,
) {
    /** Loopback never uses DNS; other lookups must match the configured host. */
    fun resolveHost(hostname: String, remoteLookup: (String) -> List<InetAddress>): List<InetAddress> {
        require(normalizeHost(hostname) == host) { "Inference destination changed" }
        if (!isLoopback) return remoteLookup(hostname)
        val bytes = if (host == "::1") ByteArray(16).also { it[15] = 1 }
            else byteArrayOf(127, 0, 0, 1)
        return listOf(InetAddress.getByAddress(bytes))
    }

    companion object {
        fun parse(endpoint: String): LinuxInferenceEndpoint {
            require(endpoint.length in 1..4096) { "Invalid inference endpoint" }
            val uri = try { URI(endpoint) } catch (_: Exception) {
                throw IllegalArgumentException("Invalid inference endpoint")
            }
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.query == null && uri.fragment == null &&
                (uri.port == -1 || uri.port in 1..65535)) {
                "Inference endpoint must be HTTP(S) without credentials, query or fragment"
            }
            val host = normalizeHost(uri.host)
            val local = host in setOf("localhost", "127.0.0.1", "::1")
            require(uri.scheme == "https" || local) {
                "Off-device inference requires HTTPS"
            }
            return LinuxInferenceEndpoint(host, local)
        }

        private fun normalizeHost(host: String): String =
            host.removePrefix("[").removeSuffix("]").lowercase(Locale.ROOT)
    }
}
