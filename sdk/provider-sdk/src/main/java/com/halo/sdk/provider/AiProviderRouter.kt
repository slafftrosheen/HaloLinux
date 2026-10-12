package com.halo.sdk.provider

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Immutable execution target resolved by the AI routing boundary.
 *
 * Every cognitive transaction must resolve exactly one target before
 * generation starts. Provider/model/capabilities must not be independently
 * re-resolved later in the same transaction.
 */
data class AiExecutionTarget(
    val provider: AiInferenceProvider,
    val model: AiModel,
    val providerCapabilities: Set<AiCapability>,
    val effectiveCapabilities: Set<AiCapability> = providerCapabilities.intersect(
        model.inputCapabilities +
            (if (model.supportsTools && AiCapability.TOOLS in providerCapabilities) {
                setOf(AiCapability.TOOLS)
            } else emptySet()) +
            (if (model.supportsStreaming && AiCapability.STREAMING in providerCapabilities) {
                setOf(AiCapability.STREAMING)
            } else emptySet())
    ),
    /** Exact protocol resolved with this immutable target, when known. */
    val toolProtocol: ToolCallProtocol? = null,
) {
    val providerId: String
        get() = provider.id

    val modelId: String
        get() = model.id
}

enum class TargetRejectionReason {
    PROVIDER_NOT_READY,
    MODEL_NOT_INSTALLED,
    MODEL_NOT_ADVERTISED,
    CAPABILITY_MISMATCH,
    INPUT_MODALITY_UNSUPPORTED,
    TOOLS_UNSUPPORTED,
    STREAMING_UNSUPPORTED,
    CONTEXT_WINDOW_TOO_SMALL,
    REMOTE_CONTEXT_FORBIDDEN,
    MEMORY_PREFLIGHT_FAILED,
    NETWORK_UNAVAILABLE,
    CONFIGURATION_MISSING,
    POLICY_EXCLUDED,
    PROVIDER_COOLDOWN,
    RESOURCE_BUSY,
    ROLE_DISABLED,
}

data class RejectedAiTarget(
    val providerId: String,
    val modelId: String?,
    val reason: TargetRejectionReason,
)

data class AiRoutingDecision(
    val policy: AiRoutingPolicy,
    val selectedProviderId: String?,
    val selectedModelId: String?,
    val resolvedProviderId: String?,
    val resolvedModelId: String?,
    val rejectedTargets: List<RejectedAiTarget>
)

/** Content-free evidence for the most recent generation attempted through the router. */
data class AiExecutionDiagnostics(
    val inFlight: Boolean = false,
    val providerId: String? = null,
    val modelId: String? = null,
    val startedAtMs: Long? = null,
    val lastSuccessAtMs: Long? = null,
    val lastLatencyMs: Long? = null,
    val lastBackend: String? = null,
    val lastFailureStage: String? = null,
    val lastFailureType: String? = null,
    val lastFailureMessage: String? = null,
    val diagnosticsId: String? = null,
    val recordedAtMs: Long? = null,
)

/**
 * Interface for routing AI requests to the appropriate provider.
 */
interface AiProviderRouter {
    val lastRoutingDecision: StateFlow<AiRoutingDecision?>
        get() = MutableStateFlow(null)

    val executionDiagnostics: StateFlow<AiExecutionDiagnostics>
        get() = MutableStateFlow(AiExecutionDiagnostics())

    val selectedProviderId: StateFlow<String?>
    val selectedModelId: StateFlow<String?>
    val diagnosticsFlow: StateFlow<List<AiProviderDiagnostic>>
        get() = MutableStateFlow(emptyList())

    /** Policy used to resolve execution targets. Read-only projection for observers. */
    val routingPolicy: StateFlow<AiRoutingPolicy>
        get() = MutableStateFlow(AiRoutingPolicy.AUTOMATIC)

    val roleBindings: StateFlow<Map<AiModelRole, AiRoleBinding>>
        get() = MutableStateFlow(emptyMap())

    fun setRoleBinding(binding: AiRoleBinding) {}

    /** Reads supplied cached metadata and configuration; never refreshes a catalogue. */
    suspend fun candidatesForRole(
        role: AiModelRole,
        catalogue: List<AiModelCatalogEntry>,
        configuredProviderIds: Set<String>,
    ): List<AiRoleCandidate> = emptyList()

    fun roleBinding(role: AiModelRole): AiRoleBinding = 
        roleBindings.value[role] ?: AiRoleBinding(role, null, null, AiRoleRequirements.defaultSelection(role))

    fun selectProvider(id: String)

    fun selectModel(modelId: String?)

    /** Atomically changes provider and model so observers never see a mixed target. */
    fun selectTarget(ref: AiModelRef) {
        selectProvider(ref.providerId)
        selectModel(ref.modelId)
    }

    fun getAllProviders(): List<AiProvider>

    /**
     * Resolve the complete execution target for a request and workload spec.
     */
    suspend fun resolveExecutionTarget(
        request: AiRequest,
        workload: AiWorkloadSpec = AiWorkloadSpec(),
    ): AiExecutionTarget

    suspend fun resolveExecutionTarget(
        request: AiRequest
    ): AiExecutionTarget = resolveExecutionTarget(request, AiWorkloadSpec())

    /** Resolve a different target after a pre-output execution failure, if policy permits it. */
    suspend fun resolveFallbackExecutionTarget(
        request: AiRequest,
        workload: AiWorkloadSpec,
        failedProviderId: String,
    ): AiExecutionTarget? = null

    /** Exact target resolution for durable recovery; never falls back or mutates selection. */
    suspend fun resolvePinnedExecutionTarget(
        request: AiRequest,
        workload: AiWorkloadSpec,
        providerId: String,
        modelId: String,
    ): AiExecutionTarget {
        val provider = getAllProviders().firstOrNull { it.id == providerId } as? AiInferenceProvider
            ?: throw AiProviderException(AiErrorType.PROVIDER_UNAVAILABLE, "Durable provider $providerId is no longer available")
        if (!workload.allowRemote && !provider.isLocal) {
            throw AiProviderException(
                AiErrorType.UNSUPPORTED_CAPABILITY,
                "Durable remote provider is no longer permitted for this workload",
                AiFailureDetails(recoverability = Recoverability.USER_ACTION_REQUIRED),
            )
        }
        val status = (provider as? ModelReadinessReporting)?.statusForModel(modelId) ?: provider.getStatus()
        status.cooldown?.takeIf { it.isActive() }?.let { cooldown ->
            throw AiProviderException(
                cooldown.reason,
                status.message ?: "Durable provider is temporarily cooling down",
                AiFailureDetails(retryAfterMs = cooldown.remainingMs(), recoverability = Recoverability.RETRY_AFTER),
            )
        }
        val ready = status.state == ProviderStatus.State.READY ||
            status.readiness is ProviderReadiness.ConfiguredCold || status.readiness is ProviderReadiness.Ready
        if (!ready) throw AiProviderException(AiErrorType.PROVIDER_UNAVAILABLE, "Durable provider $providerId is not ready")
        val model = provider.listModels().firstOrNull { it.id == modelId }
            ?: throw AiProviderException(AiErrorType.MODEL_NOT_FOUND, "Durable model $modelId is no longer advertised by $providerId")
        val evaluator = AiTargetEligibilityEvaluator()
        val snapshot = evaluator.capabilities(provider, model)
        val effectiveCapabilities = snapshot.effectiveCapabilities
        if (!evaluator.evaluate(model, snapshot, request, workload, routingPolicy.value).eligible)
            throw AiProviderException(AiErrorType.UNSUPPORTED_CAPABILITY, "Durable target no longer satisfies the workload")
        request.validateAgainst(effectiveCapabilities)
        return AiExecutionTarget(provider, model, effectiveCapabilities, effectiveCapabilities, snapshot.toolProtocol)
    }

    /**
     * Legacy provider-only resolution.
     */
    suspend fun getEligibleProvider(
        request: AiRequest
    ): AiProvider

    suspend fun execute(
        request: AiRequest,
        context: AiContext = AiContext.EMPTY
    ): AiResponse

    /** Execute through the complete routing, policy, validation, telemetry and fallback boundary. */
    suspend fun execute(
        request: AiRequest,
        workload: AiWorkloadSpec,
        context: AiContext = AiContext.EMPTY,
    ): AiResponse = execute(request, context)

    suspend fun diagnostics(): List<AiProviderDiagnostic> = emptyList()

    fun start()

    fun stop()
}
