package com.halo.adapters.inference

import com.halo.sdk.provider.AiCapability
import com.halo.sdk.provider.AiExecutionLocation
import com.halo.sdk.provider.AiInferenceProvider
import com.halo.sdk.provider.AiWireProtocol
import com.halo.sdk.provider.ConfiguredOpenAiCompatibleProvider
import com.halo.sdk.provider.CredentialProvider
import com.halo.sdk.provider.ModelDiscoveryMode
import com.halo.sdk.provider.OpenAiCompatibleTransport
import com.halo.sdk.provider.ProviderAccessKind
import com.halo.sdk.provider.ProviderAuthKind
import com.halo.sdk.provider.RemoteProviderDescriptor

/**
 * Linux adapter deliberately delegates wire parsing and provider behavior to the
 * canonical Halo provider SDK. Do not implement a second OpenAI protocol here.
 */
class LinuxOpenAiCompatibleProvider(
    config: LinuxProviderConfig,
    transport: OpenAiCompatibleTransport,
    credentialProvider: CredentialProvider,
) : AiInferenceProvider by ConfiguredOpenAiCompatibleProvider(
    descriptor = RemoteProviderDescriptor(
        id = config.id,
        displayName = config.displayName,
        protocol = AiWireProtocol.OPENAI_CHAT_COMPLETIONS,
        authKinds = setOf(config.authKind),
        accessKind = config.accessKind,
        modelDiscovery = ModelDiscoveryMode.REMOTE_CATALOG,
        capabilities = setOf(AiCapability.TEXT, AiCapability.STREAMING),
        executionLocation = config.executionLocation,
    ),
    transport = transport,
    credentialProvider = credentialProvider,
    allowInsecureLocalCredentialTransport = config.allowInsecureLocalCredentialTransport,
)

data class LinuxProviderConfig(
    val id: String = "openai-compatible",
    val displayName: String = "OpenAI-compatible endpoint",
    val baseUrl: String,
    val executionLocation: AiExecutionLocation = AiExecutionLocation.LOCAL_NETWORK,
    val authKind: ProviderAuthKind = ProviderAuthKind.NONE,
    val accessKind: ProviderAccessKind = ProviderAccessKind.CUSTOM,
    val allowInsecureLocalCredentialTransport: Boolean = false,
)

object LinuxProviderPresets {
    fun ollamaLocal(baseUrl: String = "http://127.0.0.1:11434/v1") = LinuxProviderConfig(
        id = "ollama-local",
        displayName = "Ollama (local)",
        baseUrl = baseUrl,
        executionLocation = AiExecutionLocation.LOCAL_NETWORK,
        authKind = ProviderAuthKind.NONE,
        accessKind = ProviderAccessKind.LOCAL,
    )
}
