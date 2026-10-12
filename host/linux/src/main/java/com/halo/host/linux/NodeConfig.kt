package com.halo.host.linux

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor

data class NodeConfig(
    val displayName: String,
    val providerEndpoint: String?,
    val providerModel: String?,
    val dataDir: String?,
    val audioEnabled: Boolean,
    val configPath: String?,
) {
    val providerConfigured: Boolean get() = !providerEndpoint.isNullOrBlank() && !providerModel.isNullOrBlank()
    val apiKey: String? get() = System.getenv("HALO_INFERENCE_API_KEY")?.takeIf(String::isNotBlank)
}

object NodeConfiguration {
    fun load(): NodeConfig {
        val home = Path.of(System.getProperty("user.home"))
        val xdg = System.getenv("XDG_CONFIG_HOME")?.let(Path::of) ?: home.resolve(".config")
        val path = System.getenv("HALO_CONFIG_FILE")?.let(Path::of) ?: xdg.resolve("halo-linux/config.yaml")
        if (!Files.isRegularFile(path)) return parse("", home, null)
        require(Files.size(path) <= 100_000) { "Configuration file too large" }
        return parse(Files.readString(path), home, path.toString())
    }

    /** Parse with an explicit home for fast, deterministic no-hardware tests. */
    fun parse(yaml: String, home: Path, source: String? = null): NodeConfig {
        require(yaml.length <= 100_000) { "Configuration file too large" }
        val options = LoaderOptions().apply {
            codePointLimit = 100_000
            maxAliasesForCollections = 0
            nestingDepthLimit = 20
        }
        val raw = Yaml(SafeConstructor(options)).load<Any?>(yaml)
        val values: Map<*, *> = if (raw == null) emptyMap<String, Any>()
            else (raw as? Map<*, *>) ?: throw IllegalArgumentException("Invalid YAML root")
        fun section(name: String): Map<*, *> = when (val entry = values[name]) {
            null -> emptyMap<String, Any>()
            is Map<*, *> -> entry
            else -> throw IllegalArgumentException("Invalid configuration section: $name")
        }
        val node = section("node")
        val inference = section("inference")
        val storage = section("storage")
        val audio = section("audio")
        val endpoint = (inference["endpoint"] as? String)?.trim()?.takeIf(String::isNotBlank)
        if (endpoint != null) {
            val uri = runCatching { URI(endpoint) }.getOrElse {
                throw IllegalArgumentException("Invalid inference endpoint URL")
            }
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "Inference endpoint must be an HTTP(S) URL without embedded credentials, query, or fragment"
            }
        }
        if (endpoint != null && endpoint.startsWith("http://", ignoreCase = true)) {
            val host = URI(endpoint).host?.lowercase().orEmpty()
            require(host in setOf("localhost", "127.0.0.1", "::1")) {
                "Unencrypted inference endpoints are supported on loopback only. Use HTTPS for LAN/remote providers."
            }
        }
        val model = (inference["model"] as? String)?.trim()?.takeIf(String::isNotBlank)
        // A half-configured provider is a configuration error, not "offline
        // mode": otherwise users can spend hours diagnosing disabled inference.
        require((endpoint == null) == (model == null)) {
            "Inference endpoint and model must both be set, or both omitted"
        }
        require(model == null || model.length <= 256) { "Invalid model identifier" }
        val configuredPath = (storage["data_dir"] as? String)?.trim()
            ?.takeIf { it.isNotBlank() }?.let { value ->
                // Resolve once, independently of the daemon's working directory.
                // Reject relative paths rather than silently moving private data
                // when transitioning from a desktop terminal to systemd.
                val selected = if (value.startsWith("~/")) {
                    home.resolve(value.removePrefix("~/"))
                } else {
                    Path.of(value)
                }
                require(selected.isAbsolute) {
                    "storage.data_dir must be absolute or start with ~/"
                }
                selected.normalize().toString()
            }
        val enabled = audio["enabled"] as? Boolean ?: false
        return NodeConfig(
            displayName = (node["display_name"] ?: node["name"]) as? String ?: "Halo Linux Node",
            providerEndpoint = endpoint,
            providerModel = model,
            dataDir = configuredPath,
            audioEnabled = enabled,
            configPath = source,
        )
    }
}
