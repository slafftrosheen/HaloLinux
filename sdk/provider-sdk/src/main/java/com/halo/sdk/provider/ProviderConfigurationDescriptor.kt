package com.halo.sdk.provider

/** Small bounded schema for settings; values and credentials never belong to descriptors. */
enum class ProviderFieldKind { SECRET, TEXT, ENDPOINT, ENUM, OAUTH_ACTION }
data class ProviderConfigurationField(val id: String, val label: String, val kind: ProviderFieldKind, val options: List<String> = emptyList())
data class ProviderConfigurationDescriptor(
    val providerId: String,
    val displayName: String,
    val location: AiExecutionLocation,
    val fields: List<ProviderConfigurationField>,
    val protocol: String,
    val access: String,
) {
    init { require(fields.size <= 8) }
    val endpointEditable: Boolean get() = fields.any { it.kind == ProviderFieldKind.ENDPOINT }
    val supportsOAuth: Boolean get() = fields.any { it.kind == ProviderFieldKind.OAUTH_ACTION }
    val supportsModelDiscovery: Boolean get() = true
    val supportsConnectionTest: Boolean get() = true
}
interface ProviderConfigurationReporting { val configurationDescriptor: ProviderConfigurationDescriptor }

/** Classifies endpoint metadata without DNS or network I/O. Public hosts are remote. */
fun endpointExecutionLocation(endpoint: String): AiExecutionLocation {
    val host = runCatching { java.net.URI(endpoint).host?.lowercase()?.trim('[', ']') }.getOrNull()
        ?: return AiExecutionLocation.REMOTE
    val hostParts = host.split('.')
    val octets = hostParts.mapNotNull { it.toIntOrNull()?.takeIf { value -> value in 0..255 } }
    val privateIpv4 = hostParts.size == 4 && octets.size == 4 && (octets[0] == 10 || octets[0] == 127 ||
        (octets[0] == 192 && octets[1] == 168) || (octets[0] == 172 && octets[1] in 16..31) ||
        (octets[0] == 100 && octets[1] in 64..127))
    return if (host in setOf("localhost", "::1", "tailscale") || host.endsWith(".local") || host.endsWith(".ts.net") || privateIpv4)
        AiExecutionLocation.LOCAL_NETWORK else AiExecutionLocation.REMOTE
}
