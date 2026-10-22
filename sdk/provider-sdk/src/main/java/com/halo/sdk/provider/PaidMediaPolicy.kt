package com.halo.sdk.provider

/** Separate feature-level consent for remote media inference, whose price is not reliably known.
 * This is not a privacy grant, model qualification, tool permission or provider budget.
 * On-device processing and ordinary text retain their existing policy.
 */
object PaidMediaPolicy {
    const val CONSENT_KEY = "paid_media_inference_consent_v1"
    private val media = setOf(AiCapability.IMAGE, AiCapability.AUDIO, AiCapability.VIDEO)

    fun requireAllowed(
        local: Boolean,
        request: AiRequest,
        consented: Boolean,
        workload: AiWorkloadSpec = AiWorkloadSpec(),
    ) {
        val hasMedia = request.image != null || request.audio != null || request.video != null ||
            workload.requiredCapabilities.any { it in media } ||
            workload.kind == AiWorkloadKind.VISUAL_UNDERSTANDING
        if (!local && hasMedia && !consented) throw AiProviderException(
            AiErrorType.CAPABILITY_DISABLED,
            "Remote media analysis may be charged. Enable it separately in Costs and limits before sending media.",
            AiFailureDetails(providerCode = "PAID_MEDIA_CONSENT_REQUIRED",
                recoverability = Recoverability.USER_ACTION_REQUIRED),
        )
    }
}
