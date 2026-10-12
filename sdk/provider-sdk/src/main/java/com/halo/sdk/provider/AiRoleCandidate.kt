package com.halo.sdk.provider

enum class AiRoleCandidateRejection {
    PROVIDER_NOT_CONFIGURED, MODEL_UNAVAILABLE, TEXT_UNAVAILABLE, TOOLS_UNAVAILABLE,
    VISION_UNAVAILABLE, REMOTE_FORBIDDEN, POLICY_EXCLUDED, CONTEXT_TOO_SMALL, ROLE_DISABLED,
    INPUT_UNAVAILABLE, STREAMING_UNAVAILABLE,
}

data class AiRoleCandidate(
    val ref: AiModelRef,
    val role: AiModelRole,
    val eligible: Boolean,
    val effectiveCapabilities: Set<AiCapability>,
    val executionLocation: AiExecutionLocation,
    val rejectionReasons: Set<AiRoleCandidateRejection> = emptySet(),
    val toolProtocol: ToolCallProtocol = ToolCallProtocol.NONE,
)

object AiRoleRequirements {
    fun defaultSelection(role: AiModelRole): AiRoleSelection =
        if (role == AiModelRole.AGENTIC || role == AiModelRole.VISION) AiRoleSelection.AUTOMATIC else AiRoleSelection.INHERIT_DEFAULT

    fun capabilities(role: AiModelRole): Set<AiCapability> = when (role) {
        AiModelRole.AGENTIC -> setOf(AiCapability.TEXT, AiCapability.TOOLS)
        AiModelRole.VISION -> setOf(AiCapability.TEXT, AiCapability.IMAGE)
        else -> setOf(AiCapability.TEXT)
    }

    fun workload(role: AiModelRole) = AiWorkloadSpec(
        preferredRole = role, requiredCapabilities = capabilities(role),
        tools = if (role == AiModelRole.AGENTIC) AiFeatureRequirement.REQUIRED else AiFeatureRequirement.NOT_NEEDED,
    )

    fun role(workload: AiWorkloadSpec): AiModelRole = when {
        AiCapability.IMAGE in workload.requiredCapabilities || AiCapability.VIDEO in workload.requiredCapabilities -> AiModelRole.VISION
        workload.tools == AiFeatureRequirement.REQUIRED || workload.kind == AiWorkloadKind.TOOL_ASSISTED -> AiModelRole.AGENTIC
        else -> workload.preferredRole ?: AiModelRole.CHAT
    }
}

data class AiTargetCapabilitySnapshot(
    val effectiveCapabilities: Set<AiCapability>,
    val toolProtocol: ToolCallProtocol,
)

/** Exact reporting is authoritative. This boundary never loads, probes or generates a model. */
class AiTargetEligibilityEvaluator {
    suspend fun capabilities(provider: AiInferenceProvider, model: AiModel): AiTargetCapabilitySnapshot {
        val caps = if (provider is ModelInputCapabilityReporting) provider.capabilitiesForModel(model.id)
        else provider.getCapabilities().intersect(model.inputCapabilities +
            (if (model.supportsTools) setOf(AiCapability.TOOLS) else emptySet()) +
            (if (model.supportsStreaming) setOf(AiCapability.STREAMING) else emptySet()))
        val protocol = if (provider is ToolProtocolReporting) provider.toolCallProtocol(model.id)
        else if (AiCapability.TOOLS in caps) ToolCallProtocol.NATIVE else ToolCallProtocol.NONE
        return AiTargetCapabilitySnapshot(caps, protocol)
    }

    fun evaluate(
        model: AiModel, snapshot: AiTargetCapabilitySnapshot, request: AiRequest,
        workload: AiWorkloadSpec, policy: AiRoutingPolicy,
    ): AiRoleCandidate {
        val role = AiRoleRequirements.role(workload)
        val caps = snapshot.effectiveCapabilities
        val required = workload.requiredCapabilities + AiRoleRequirements.capabilities(role)
        val reasons = buildSet {
            if (AiCapability.TEXT in required && AiCapability.TEXT !in caps) add(AiRoleCandidateRejection.TEXT_UNAVAILABLE)
            if ((AiCapability.TOOLS in required || workload.tools == AiFeatureRequirement.REQUIRED) &&
                (AiCapability.TOOLS !in caps || !snapshot.toolProtocol.supportsToolLoop)) add(AiRoleCandidateRejection.TOOLS_UNAVAILABLE)
            if ((AiCapability.IMAGE in required || request.image != null) &&
                (AiCapability.IMAGE !in caps || !model.hasVerifiedImageInput())) add(AiRoleCandidateRejection.VISION_UNAVAILABLE)
            if (!workload.allowRemote && model.executionLocation != AiExecutionLocation.ON_DEVICE) add(AiRoleCandidateRejection.REMOTE_FORBIDDEN)
            if (policy == AiRoutingPolicy.LOCAL_ONLY && model.executionLocation == AiExecutionLocation.REMOTE) add(AiRoleCandidateRejection.POLICY_EXCLUDED)
            if (workload.minimumContextTokens > 0 && (model.contextLength ?: 0) < workload.minimumContextTokens) add(AiRoleCandidateRejection.CONTEXT_TOO_SMALL)
            if (workload.streaming == AiFeatureRequirement.REQUIRED && AiCapability.STREAMING !in caps) add(AiRoleCandidateRejection.STREAMING_UNAVAILABLE)
            if (required.any { it !in caps } ||
                (!request.text.isNullOrBlank() && AiCapability.TEXT !in caps) ||
                (request.audio != null && AiCapability.AUDIO !in caps) ||
                (request.video != null && AiCapability.VIDEO !in caps)) add(AiRoleCandidateRejection.INPUT_UNAVAILABLE)
        }
        return AiRoleCandidate(AiModelRef(model.providerId, model.id), role, reasons.isEmpty(), caps,
            model.executionLocation, reasons, snapshot.toolProtocol)
    }
}

/** Hard eligibility is applied before ranking; this never relaxes privacy requirements. */
object AiRoleCandidateRanking {
    fun comparator(role: AiModelRole, readyDefault: AiModelRef?): Comparator<AiRoleCandidate> =
        compareBy<AiRoleCandidate> {
            when (role) {
                AiModelRole.FAST -> if (it.ref == readyDefault) -1 else it.executionLocation.ordinal
                AiModelRole.AGENTIC -> if (it.toolProtocol == ToolCallProtocol.NATIVE) 0 else 1
                else -> it.executionLocation.ordinal
            }
        }.thenBy { it.executionLocation.ordinal }.thenBy { it.ref.providerId }.thenBy { it.ref.modelId }
}
