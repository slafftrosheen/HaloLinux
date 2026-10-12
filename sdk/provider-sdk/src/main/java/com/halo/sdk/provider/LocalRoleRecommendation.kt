package com.halo.sdk.provider

const val LOCAL_ROLE_QUALIFICATION_COMPAT_EPOCH = 1

data class LocalRoleEvidenceIdentity(
    val ref: AiModelRef, val artifactFingerprint: String, val deviceFingerprint: String,
    val runtime: String, val runtimeVersion: String, val compatibilityEpoch: Int,
    val backend: String, val contextTokens: Int?, val threads: Int, val gpuLayers: Int,
    val probeSuiteVersion: Int,
)
enum class LocalRoleRecommendationConfidence { NONE, SINGLE_MEASURED, COMPARATIVE }
data class LocalRoleCandidateEvidence(
    val identity: LocalRoleEvidenceIdentity, val testedAtMs: Long, val stable: Boolean,
    val memoryPressure: String, val artifactBytes: Long, val loadMs: Long?,
    val fastPass: Boolean, val warmSamples: Int, val warmMedianMs: Long?,
    val chatPassed: Int, val chatTotal: Int, val reasoningPassed: Int, val reasoningTotal: Int,
    val agenticPass: Boolean, val protocol: ToolCallProtocol, val continuationPass: Boolean,
    val verifiedVision: Boolean,
)
data class LocalRoleRecommendation(val role: AiModelRole, val recommended: AiModelRef?,
    val candidates: List<LocalRoleCandidateEvidence>, val confidence: LocalRoleRecommendationConfidence)

/** Pure projection. Names, purpose hints and family metadata never enter this policy. */
object LocalRoleRecommendationEngine {
    fun recommend(role: AiModelRole, evidence: List<LocalRoleCandidateEvidence>,
        current: Set<LocalRoleEvidenceIdentity>, eligibility: List<AiRoleCandidate>): LocalRoleRecommendation {
        val allowed = eligibility.filter { it.role == role && it.eligible && it.executionLocation == AiExecutionLocation.ON_DEVICE }.map { it.ref }.toSet()
        // Latest attempt wins, including instability. An older pass cannot hide a recent failure.
        val measured = evidence.filter { it.identity in current }.groupBy { it.identity }
            .values.map { rows -> rows.maxBy { it.testedAtMs } }.filter {
                it.identity.ref in allowed && it.stable && it.memoryPressure != "CRITICAL" && when (role) {
                    AiModelRole.FAST -> it.fastPass && it.warmSamples == 3 && it.warmMedianMs != null && chatPass(it)
                    AiModelRole.CHAT -> chatPass(it)
                    AiModelRole.REASONING -> chatPass(it) && it.reasoningTotal == 3 && it.reasoningPassed == 3
                    AiModelRole.AGENTIC -> it.agenticPass && it.continuationPass && it.protocol.supportsToolLoop
                    AiModelRole.VISION -> it.verifiedVision
                }
            }
        val latency = compareBy<LocalRoleCandidateEvidence> { it.warmMedianMs ?: Long.MAX_VALUE }
        val order = when (role) {
            AiModelRole.CHAT -> compareByDescending<LocalRoleCandidateEvidence> { it.reasoningPassed }
                .thenByDescending { it.identity.contextTokens ?: 0 }.then(latency)
            AiModelRole.AGENTIC -> compareBy<LocalRoleCandidateEvidence> { if (it.protocol == ToolCallProtocol.NATIVE) 0 else 1 }.then(latency)
            AiModelRole.REASONING -> compareBy<LocalRoleCandidateEvidence> { if (it.memoryPressure == "NORMAL") 0 else 1 }.then(latency)
            else -> latency
        }
        val sorted = measured.sortedWith(order.thenBy { it.loadMs ?: Long.MAX_VALUE }
            .thenBy { it.artifactBytes }.thenBy { it.identity.ref.modelId })
        // Different execution backends are displayed, but never labelled a comparative winner.
        val comparable = sorted.map { listOf(it.identity.deviceFingerprint, it.identity.runtimeVersion,
            it.identity.compatibilityEpoch, it.identity.backend, it.identity.probeSuiteVersion) }.distinct().size <= 1
        return LocalRoleRecommendation(role, sorted.firstOrNull()?.identity?.ref?.takeIf { comparable }, sorted, when {
            sorted.isEmpty() || !comparable -> LocalRoleRecommendationConfidence.NONE
            sorted.size == 1 -> LocalRoleRecommendationConfidence.SINGLE_MEASURED
            else -> LocalRoleRecommendationConfidence.COMPARATIVE
        })
    }
    private fun chatPass(e: LocalRoleCandidateEvidence) = e.chatPassed == 3 && e.chatTotal == 3
}
