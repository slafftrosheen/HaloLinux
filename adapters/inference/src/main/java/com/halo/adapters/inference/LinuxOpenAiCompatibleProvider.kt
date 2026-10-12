package com.halo.adapters.inference

import com.halo.libraries.common.core.Identifier
import com.halo.sdk.provider.AiCapability
import com.halo.sdk.provider.AiContext
import com.halo.sdk.provider.AiInferenceProvider
import com.halo.sdk.provider.AiModel
import com.halo.sdk.provider.AiProvider
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
 * This provider connects to local or remote OpenAI-compatible endpoints
 * (Ollama, llama.cpp server, vLLM, etc.) for text generation.
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

    override val configurationDescriptor: ProviderConfigurationDescriptor
        get() = ProviderConfigurationDescriptor(
            config.id,
            config.displayName,
            config.executionLocation,
            if (config.authKind != ProviderAuthKind.NONE)
                listOf(ProviderConfigurationField("credential", "API key / token", ProviderFieldKind.SECRET))
            else emptyList(),
            config.protocol.name,
            config.accessKind.name,
        )

    override fun setCredential(credential: Credential) {
        credentialProvider.setCredential(credential)
        configurationChanged()
    }

    override fun clearCredential() {
        credentialProvider.clearCredential()
        configurationChanged()
    }

    override val id: String get() = config.id
    override val displayName: String get() = config.displayName
    override val isLocal: Boolean get() = config.executionLocation == AiExecutionLocation.LOCAL_NETWORK

    private val _status = MutableStateFlow(ProviderStatus.UNCONFIGURED)
    override val status: StateFlow<ProviderStatus> = _status.asStateFlow()

    private val _authInfo = MutableStateFlow(ProviderAuthInfo(
        authKind = config.authKind,
        authState = if (credentialProvider.hasCredential()) ProviderAuthState.CONFIGURED else ProviderAuthState.NOT_CONFIGURED,
    ))
    val authInfo: StateFlow<ProviderAuthInfo> = _authInfo.asStateFlow()

    override fun configurationChanged() {
        _status.value = if (credentialProvider.hasCredential()) configuredCold() else ProviderStatus.UNCONFIGURED
        _authInfo.value = _authInfo.value.copy(authState = if (credentialProvider.hasCredential()) ProviderAuthState.CONFIGURED else ProviderAuthState.NOT_CONFIGURED)
    }

    override suspend fun getCapabilities(): Set<AiCapability> = config.capabilities

    override suspend fun getStatus(): ProviderStatus = withContext(Dispatchers.IO) {
        _status.value = when {
            !credentialProvider.hasCredential() -> ProviderStatus.UNCONFIGURED
            _status.value.state == ProviderStatus.State.UNCONFIGURED -> configuredCold()
            else -> _status.value
        }
        _status.value
    }

    override suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        val cred = credentialProvider.credential()
        if (cred == null) return@withContext false
        val (header, value) = credentialHeader(cred)
        try {
            transport.testConnection(header, value, config.allowInsecureLocalCredentialTransport).also { ok ->
                if (ok) setAuthState(ProviderAuthState.CONNECTED)
            }
        } catch (e: AiProviderException) {
            setAuthState(if (e.type == AiErrorType.AUTHENTICATION_FAILED) ProviderAuthState.FAILED else ProviderAuthState.FAILED, e.message)
            false
        }
    }

    override suspend fun generate(request: AiRequest, context: AiContext): AiResponse {
        val cred = credentialProvider.credential() ?: throw missingCredential()
        val (header, value) = credentialHeader(cred)
        val model = request.modelId?.takeIf { it.isNotBlank() }
            ?: throw AiProviderException(AiErrorType.MODEL_NOT_SELECTED, "Select a ${displayName} model first")
        
        return try {
            transport.generate(request, context, model, header, value, systemPrompt, config.allowInsecureLocalCredentialTransport).also {
                if (it.text.isNotBlank() || it.toolCalls.isNotEmpty()) setAuthState(ProviderAuthState.CONNECTED)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiProviderException) {
            if (e.type == AiErrorType.AUTHENTICATION_FAILED) setAuthState(ProviderAuthState.EXPIRED, e.message)
            throw e
        }
    }

    override fun stream(request: AiRequest, context: AiContext): Flow<AiStreamEvent> = flow {
        val cred = credentialProvider.credential() ?: throw missingCredential()
        val (header, value) = credentialHeader(cred)
        val model = request.modelId?.takeIf { it.isNotBlank() }
            ?: throw AiProviderException(AiErrorType.MODEL_NOT_SELECTED, "Select a ${displayName} model first")
        transport.stream(request, context, model, header, value, systemPrompt, config.allowInsecureLocalCredentialTransport).collect { emit(it) }
    }

    override suspend fun listModels(): List<AiModel> = withContext(Dispatchers.IO) {
        when (config.modelDiscovery) {
            ModelDiscoveryMode.STATIC_HINTS -> staticHints.ifEmpty { fallbackStaticModels() }
            ModelDiscoveryMode.REMOTE_CATALOG -> {
                val cred = credentialProvider.credential()
                if (cred == null) return@withContext emptyList()
                val (header, value) = credentialHeader(cred)
                try {
                    transport.fetchRemoteModelList(header, value, config.allowInsecureLocalCredentialTransport, staticHints, config.freeModelIds, config.fallbackWhenUnauthenticated).also {
                        if (it.isNotEmpty()) setAuthState(ProviderAuthState.CONNECTED)
                    }
                } catch (e: AiProviderException) {
                    if (e.type == AiErrorType.AUTHENTICATION_FAILED) setAuthState(ProviderAuthState.EXPIRED, e.message)
                    throw e
                }
            }
            ModelDiscoveryMode.NONE -> emptyList()
        }.map { it.copy(executionLocation = config.executionLocation) }
    }

    fun setAuthState(state: ProviderAuthState, failure: String? = null) {
        _authInfo.value = _authInfo.value.copy(authState = state, lastAuthFailure = failure)
    }

    fun setQuotaSnapshot(snapshot: ProviderQuotaSnapshot) {
        _authInfo.value = _authInfo.value.copy(quotaSnapshot = snapshot)
    }

    private fun credentialHeader(cred: Credential): Pair<String?, String?> = when {
        cred.id.endsWith(".none") -> null to null
        cred.id.endsWith(".bearer") || cred.id.endsWith(".token") -> "Authorization" to "Bearer ${cred.value}"
        cred.id.endsWith(".api_key") -> "Authorization" to "Bearer ${cred.value}"
        config.authKind == ProviderAuthKind.API_KEY -> "Authorization" to "Bearer ${cred.value}"
        config.authKind == ProviderAuthKind.BEARER_TOKEN -> "Authorization" to "Bearer ${cred.value}"
        config.authKind == ProviderAuthKind.GOOGLE_OAUTH -> "Authorization" to "Bearer ${cred.value}"
        config.authKind == ProviderAuthKind.DEVICE_OAUTH -> "Authorization" to "Bearer ${cred.value}"
        config.authKind == ProviderAuthKind.NONE -> null to null
        else -> "Authorization" to "Bearer ${cred.value}"
    }

    private fun missingCredential() = AiProviderException(
        AiErrorType.AUTHENTICATION_FAILED,
        "${displayName} is not configured",
        AiFailureDetails(recoverability = Recoverability.USER_ACTION_REQUIRED),
    )

    private fun configuredCold() = ProviderStatus(
        ProviderStatus.State.STANDBY,
        "Configured; check connection to verify access",
        ProviderReadiness.ConfiguredCold,
    )

    private fun fallbackStaticModels(): List<AiModel> = staticHints.ifEmpty {
        listOf(
            AiModel(
                id = "default",
                displayName = displayName,
                providerId = id,
                inputCapabilities = setOf(AiCapability.TEXT),
                contextLength = null,
                supportsStreaming = true,
                supportsTools = false,
            ),
        )
    }
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
    val accessKind: com.halo.sdk.provider.ProviderAccessKind = com.halo.sdk.provider.ProviderAccessKind.REMOTE,
    val protocol: com.halo.sdk.provider.ProviderProtocol = com.halo.sdk.provider.ProviderProtocol.OPENAI_COMPATIBLE,
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
            accessKind = com.halo.sdk.provider.ProviderAccessKind.LOCAL,
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
            accessKind = com.halo.sdk.provider.ProviderAccessKind.LOCAL,
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
        )
    }
}