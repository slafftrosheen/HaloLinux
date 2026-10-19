package com.halo.adapters.inference

import com.halo.libraries.common.core.Identifier
import com.halo.sdk.provider.AiCapability
import com.halo.sdk.provider.AiContext
import com.halo.sdk.provider.AiInferenceProvider
import com.halo.sdk.provider.AiModel
import com.halo.sdk.provider.AiProviderException
import com.halo.sdk.provider.AiRequest
import com.halo.sdk.provider.AiResponse
import com.halo.sdk.provider.AiStreamEvent
import com.halo.sdk.provider.AiExecutionLocation
import com.halo.sdk.provider.Credential
import com.halo.sdk.provider.CredentialProvider
import com.halo.sdk.provider.ModelDiscoveryMode
import com.halo.sdk.provider.OpenAiCompatibleTransport
import com.halo.sdk.provider.ProviderAuthInfo
import com.halo.sdk.provider.ProviderAuthKind
import com.halo.sdk.provider.ProviderAuthState
import com.halo.sdk.provider.ProviderConfigurationDescriptor
import com.halo.sdk.provider.ProviderConfigurationField
import com.halo.sdk.provider.ProviderConfigurationReporting
import com.halo.sdk.provider.ProviderFieldKind
import com.halo.sdk.provider.ProviderQuotaSnapshot
import com.halo.sdk.provider.ProviderStatus
import com.halo.sdk.provider.ProviderStatus.State
import com.halo.sdk.provider.ProviderReadiness
import com.halo.sdk.provider.Recoverability
import com.halo.sdk.provider.AiFailureDetails
import com.halo.sdk.provider.AiErrorType
import com.halo.sdk.provider.ProviderProtocol
import com.halo.sdk.provider.ProviderAccessKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import org.slf4j.LoggerFactory

/**
 * Linux-specific OpenAI-compatible inference provider.
 * 
 * This provider delegates to the generic ConfiguredOpenAiCompatibleProvider
 * for shared OpenAI-compatible logic, while preserving Linux-specific
 * configuration and auth-free local endpoint support.
 */
class LinuxOpenAiCompatibleProvider(
    private val config: LinuxProviderConfig,
    private val transport: OpenAiCompatibleTransport,
    private val credentialProvider: CredentialProvider,
    private val systemPrompt: String? = null,
    private val optionalHeaders: Map<String, String> = emptyMap(),
    private val staticHints: List<AiModel> = emptyList(),
) : AiInferenceProvider, CredentialProvider by credentialProvider, ProviderConfigurationReporting {

    private val log = LoggerFactory.getLogger(LinuxOpenAiCompatibleProvider::class.java)

    private val descriptor = com.halo.sdk.provider.RemoteProviderDescriptor(
        id = config.id,
        displayName = config.displayName,
        protocol = config.protocol,
        authKinds = if (config.authKind == ProviderAuthKind.NONE) setOf(ProviderAuthKind.NONE) else setOf(config.authKind),
        accessKind = config.accessKind,
        modelDiscovery = config.modelDiscovery,
        capabilities = config.capabilities,
        executionLocation = config.executionLocation,
    )

    private val delegate = com.halo.sdk.provider.ConfiguredOpenAiCompatibleProvider(
        descriptor = descriptor,
        transport = transport,
        credentialProvider = credentialProvider,
        systemPrompt = systemPrompt,
        allowInsecureLocalCredentialTransport = config.allowInsecureLocalCredentialTransport,
        optionalHeaders = optionalHeaders,
        freeModelIds = config.freeModelIds,
        fallbackWhenUnauthenticated = config.fallbackWhenUnauthenticated,
        staticHints = staticHints,
    )

    override val configurationDescriptor: ProviderConfigurationDescriptor
        get() = delegate.configurationDescriptor

    override fun setCredential(credential: Credential) = delegate.setCredential(credential)
    override fun clearCredential() = delegate.clearCredential()
    override val id: String get() = delegate.id
    override val displayName: String get() = delegate.displayName
    override val isLocal: Boolean get() = delegate.isLocal
    override val status: StateFlow<ProviderStatus> = delegate.status
    val authInfo: StateFlow<ProviderAuthInfo> = delegate.authInfo

    override fun configurationChanged() = delegate.configurationChanged()

    override suspend fun getCapabilities(): Set<AiCapability> = delegate.getCapabilities()

    override suspend fun getStatus(): ProviderStatus = delegate.getStatus()

    override suspend fun testConnection(): Boolean = delegate.testConnection()

    override suspend fun generate(request: AiRequest, context: AiContext): AiResponse = delegate.generate(request, context)

    override fun stream(request: AiRequest, context: AiContext): Flow<AiStreamEvent> = delegate.stream(request, context)

    override suspend fun listModels(): List<AiModel> = delegate.listModels()

    fun setAuthState(state: ProviderAuthState, failure: String? = null) = delegate.setAuthState(state, failure)
    fun setQuotaSnapshot(snapshot: ProviderQuotaSnapshot) = delegate.setQuotaSnapshot(snapshot)
}

/**
 * Configuration for the Linux OpenAI-compatible provider.
 */
data class LinuxProviderConfig(
    val id: String,
    val displayName: String,
    val baseUrl: String,
    val executionLocation: AiExecutionLocation = AiExecutionLocation.LOCAL_NETWORK,
    val authKind: ProviderAuthKind = ProviderAuthKind.BEARER_TOKEN,
    val accessKind: ProviderAccessKind = ProviderAccessKind.REMOTE,
    val protocol: ProviderProtocol = ProviderProtocol.OPENAI_COMPATIBLE,
    val modelDiscovery: ModelDiscoveryMode = ModelDiscoveryMode.REMOTE_CATALOG,
    val capabilities: Set<AiCapability> = setOf(AiCapability.TEXT, AiCapability.STREAMING),
    val freeModelIds: Set<String> = emptySet(),
    val fallbackWhenUnauthenticated: Boolean = false,
    val allowInsecureLocalCredentialTransport: Boolean = true,
    val connectTimeoutSeconds: Int = 10,
    val readTimeoutSeconds: Int = 120,
)

/**
 * Companion object for common provider presets.
 */
object LinuxProviderPresets {
    /**
     * Ollama running locally on default port.
     */
    fun ollamaLocal(baseUrl: String = "http://localhost:11434/v1"): LinuxProviderConfig {
        return LinuxProviderConfig(
            id = "ollama-local",
            displayName = "Ollama (Local)",
            baseUrl = baseUrl,
            executionLocation = AiExecutionLocation.LOCAL,
            authKind = ProviderAuthKind.NONE,
            accessKind = ProviderAccessKind.LOCAL,
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            modelDiscovery = ModelDiscoveryMode.REMOTE_CATALOG,
        )
    }

    /**
     * llama.cpp server running locally.
     */
    fun llamaCppLocal(baseUrl: String = "http://localhost:8080/v1"): LinuxProviderConfig {
        return LinuxProviderConfig(
            id = "llamacpp-local",
            displayName = "llama.cpp (Local)",
            baseUrl = baseUrl,
            executionLocation = AiExecutionLocation.LOCAL,
            authKind = ProviderAuthKind.NONE,
            accessKind = ProviderAccessKind.LOCAL,
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
            modelDiscovery = ModelDiscoveryMode.REMOTE_CATALOG,
        )
    }

    /**
     * Custom OpenAI-compatible endpoint.
     */
    fun customOpenAiCompatible(
        id: String,
        displayName: String,
        baseUrl: String,
        authKind: ProviderAuthKind = ProviderAuthKind.BEARER_TOKEN,
    ): LinuxProviderConfig {
        return LinuxProviderConfig(
            id = id,
            displayName = displayName,
            baseUrl = baseUrl,
            authKind = authKind,
            protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        )
    }
}