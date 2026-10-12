package com.halo.sdk.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Generic OpenAI-compatible provider built on [OpenAiCompatibleTransport].
 *
 * Configuration: provider identity, display name, base endpoint, wire protocol,
 * credential reference, model discovery mode, static model hints, capabilities,
 * access metadata, provider-specific optional headers and local transport policy.
 *
 * This powers multiple presets (Groq, Cerebras, xAI, OpenCode Zen, custom
 * OpenAI-compatible) without duplicating HTTP/SSE/tool parsing per vendor.
 */
class ConfiguredOpenAiCompatibleProvider(
    private val descriptor: RemoteProviderDescriptor,
    private val transport: OpenAiCompatibleTransport,
    private val credentialProvider: CredentialProvider,
    private val systemPrompt: String? = null,
    private val allowInsecureLocalCredentialTransport: Boolean = false,
    private val optionalHeaders: Map<String, String> = emptyMap(),
    private val freeModelIds: Set<String> = emptySet(),
    private val fallbackWhenUnauthenticated: Boolean = false,
    private val staticHints: List<AiModel> = emptyList(),
) : AiInferenceProvider, CredentialProvider by credentialProvider, ProviderConfigurationReporting {

    override val configurationDescriptor: ProviderConfigurationDescriptor get() = ProviderConfigurationDescriptor(
        descriptor.id, descriptor.displayName, descriptor.executionLocation,
        if (descriptor.authKinds.any { it != ProviderAuthKind.NONE }) listOf(ProviderConfigurationField("credential", "API key / token", ProviderFieldKind.SECRET)) else emptyList(),
        descriptor.protocol.name, descriptor.accessKind.name,
    )
    override fun setCredential(credential: Credential) { credentialProvider.setCredential(credential); configurationChanged() }
    override fun clearCredential() { credentialProvider.clearCredential(); configurationChanged() }
    override val id: String get() = descriptor.id
    override val displayName: String get() = descriptor.displayName
    override val isLocal: Boolean get() = descriptor.executionLocation == AiExecutionLocation.LOCAL_NETWORK

    private val _status = MutableStateFlow(ProviderStatus.UNCONFIGURED)
    override val status: StateFlow<ProviderStatus> = _status.asStateFlow()

    private val _authInfo = MutableStateFlow(ProviderAuthInfo(
        authKind = descriptor.authKinds.firstOrNull() ?: ProviderAuthKind.NONE,
        authState = if (credentialProvider.hasCredential()) ProviderAuthState.CONFIGURED else ProviderAuthState.NOT_CONFIGURED,
    ))
    val authInfo: StateFlow<ProviderAuthInfo> = _authInfo.asStateFlow()

    override fun configurationChanged() {
        _status.value = if (credentialProvider.hasCredential()) configuredCold() else ProviderStatus.UNCONFIGURED
        _authInfo.value = _authInfo.value.copy(authState = if (credentialProvider.hasCredential()) ProviderAuthState.CONFIGURED else ProviderAuthState.NOT_CONFIGURED)
    }

    override suspend fun getCapabilities(): Set<AiCapability> = descriptor.capabilities

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
            transport.testConnection(header, value, allowInsecureLocalCredentialTransport).also { ok ->
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
            transport.generate(request, context, model, header, value, systemPrompt, allowInsecureLocalCredentialTransport).also {
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
        transport.stream(request, context, model, header, value, systemPrompt, allowInsecureLocalCredentialTransport).collect { emit(it) }
    }

    override suspend fun listModels(): List<AiModel> = withContext(Dispatchers.IO) {
        when (descriptor.modelDiscovery) {
            ModelDiscoveryMode.STATIC_HINTS -> staticHints.ifEmpty { fallbackStaticModels() }
            ModelDiscoveryMode.REMOTE_CATALOG -> {
                val cred = credentialProvider.credential()
                if (cred == null) return@withContext emptyList()
                val (header, value) = credentialHeader(cred)
                try {
                    transport.fetchRemoteModelList(header, value, allowInsecureLocalCredentialTransport, staticHints, freeModelIds, fallbackWhenUnauthenticated).also {
                        if (it.isNotEmpty()) setAuthState(ProviderAuthState.CONNECTED)
                    }
                } catch (e: AiProviderException) {
                    if (e.type == AiErrorType.AUTHENTICATION_FAILED) setAuthState(ProviderAuthState.EXPIRED, e.message)
                    throw e
                }
            }
            ModelDiscoveryMode.NONE -> emptyList()
        }.map { it.copy(executionLocation = descriptor.executionLocation) }
    }

    fun setAuthState(state: ProviderAuthState, failure: String? = null) {
        _authInfo.value = _authInfo.value.copy(authState = state, lastAuthFailure = failure)
    }

    fun setQuotaSnapshot(snapshot: ProviderQuotaSnapshot) {
        _authInfo.value = _authInfo.value.copy(quotaSnapshot = snapshot)
    }

    private fun credentialHeader(cred: Credential): Pair<String?, String?> = when {
        cred.id.endsWith(".none") -> null to null
        cred.id.endsWith(".bearer") || cred.id.endsWith(".token") ->
            "Authorization" to "Bearer ${cred.value}"
        cred.id.endsWith(".api_key") ->
            "Authorization" to "Bearer ${cred.value}"
        descriptor.authKinds.firstOrNull() == ProviderAuthKind.API_KEY ->
            "Authorization" to "Bearer ${cred.value}"
        descriptor.authKinds.firstOrNull() == ProviderAuthKind.BEARER_TOKEN ->
            "Authorization" to "Bearer ${cred.value}"
        descriptor.authKinds.firstOrNull() == ProviderAuthKind.GOOGLE_OAUTH ->
            "Authorization" to "Bearer ${cred.value}"
        descriptor.authKinds.firstOrNull() == ProviderAuthKind.DEVICE_OAUTH ->
            "Authorization" to "Bearer ${cred.value}"
        descriptor.authKinds.firstOrNull() == ProviderAuthKind.NONE -> null to null
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

/** Minimal credential contract used by remote presets. */
interface CredentialProvider {
    fun hasCredential(): Boolean
    fun credential(): Credential?
    fun setCredential(credential: Credential)
    fun clearCredential()
}

/** Portable credential reference. Values are never logged. */
data class Credential(
    val id: String,
    val value: String,
    val label: String? = null,
)
