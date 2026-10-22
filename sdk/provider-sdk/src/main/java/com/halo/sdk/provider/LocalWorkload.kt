package com.halo.sdk.provider

import com.halo.sdk.runtime.ResidencyPreference

/** These values never replace or reinterpret serialized AiModelRole values. */
enum class LocalWorkload(val legacyRole: AiModelRole? = null) {
    WAKE, VAD, STT, INTENT, FAST(AiModelRole.FAST), CHAT(AiModelRole.CHAT),
    THINK(AiModelRole.REASONING), ACTION(AiModelRole.AGENTIC), EMBED, VISION(AiModelRole.VISION), TTS;
    companion object { fun from(role: AiModelRole)=entries.single { it.legacyRole==role } }
}
enum class WorkloadSelection { AUTOMATIC, EXPLICIT, INHERITED, DISABLED }
enum class WorkloadBackend { AUTO, CPU, GPU, NNAPI, NPU, PLATFORM, REMOTE }
data class WorkloadTarget(val providerId: String,val modelId: String,val artifactSha256: String?=null)
data class WorkloadExecutionProfile(
    val backend: WorkloadBackend=WorkloadBackend.AUTO,
    val residency: ResidencyPreference=ResidencyPreference.ON_DEMAND,
    val memoryEstimateBytes: Long=0,
    val priority: Int=0,
    val timeoutMs: Long=30_000,
    val maxInputChars: Int=4096,
    val maxOutputTokens: Int=256,
    val contextTokens: Int=1024,
    val requireTaskProof: Boolean=true,
    val allowRemote: Boolean=false,
    val allowFallback: Boolean=false,
    val inheritRoutingPolicy: Boolean=true,
) {
    init { require(memoryEstimateBytes>=0 && timeoutMs in 1..300_000 && maxInputChars in 1..32768 &&
        maxOutputTokens in 1..8192 && contextTokens in 1..131072) }
}
data class WorkloadAssignment(val workload: LocalWorkload,val selection: WorkloadSelection,
    val target: WorkloadTarget?=null,val fallbacks: List<WorkloadTarget> = emptyList(),
    val execution: WorkloadExecutionProfile=WorkloadExecutionProfile()) {
    init { require(fallbacks.size<=8) }
}
data class WorkloadEnvironment(val runtime: String,val compatibilityEpoch: Int,val device: String,
    val androidVersion: String,val settingsFingerprint: String)
data class WorkloadTaskProof(val target: WorkloadTarget,val workload: LocalWorkload,val backend: WorkloadBackend,
    val environment: WorkloadEnvironment,val correct: Boolean,val stable: Boolean,val measuredAtMs: Long,
    val coldMs: Long?=null,val warmMs: Long?=null,val peakProcessBytes: Long?=null,
    val audibleConfirmed: Boolean?=null)
data class WorkloadCandidate(val target: WorkloadTarget,val declaredTasks: Set<LocalWorkload>,
    val installed: Boolean,val nativeReadable: Boolean,val resident: Boolean,val local: Boolean,
    val supportedBackends: Set<WorkloadBackend>,val memoryEstimateBytes: Long,
    val deterministic: Boolean=false,val proofs: List<WorkloadTaskProof> = emptyList())
data class WorkloadRequest(val workload: LocalWorkload,val environment: WorkloadEnvironment,
    val privateContent: Boolean=true,val remoteConsent: Boolean=false,val availableBytes: Long=0,
    val diagnostic: Boolean=false)
enum class WorkloadReadiness { READY, NEEDS_LOADING, NEEDS_QUALIFICATION, UNAVAILABLE, DISABLED }
data class WorkloadResolution(val workload: LocalWorkload,val target: WorkloadTarget?,
    val requestedBackend: WorkloadBackend,val effectiveBackend: WorkloadBackend?,val state: WorkloadReadiness,
    val reasonCode: String,val fallback: Boolean=false,val memoryClaimBytes: Long=0,
    val remotePermitted: Boolean=false,val residency: ResidencyPreference=ResidencyPreference.ON_DEMAND,
    val backendPolicy: WorkloadBackend=requestedBackend)

fun AiRoleBinding.asWorkloadAssignment()=WorkloadAssignment(LocalWorkload.from(role),when(selection) {
    AiRoleSelection.AUTOMATIC->WorkloadSelection.AUTOMATIC
    AiRoleSelection.EXPLICIT->WorkloadSelection.EXPLICIT
    AiRoleSelection.INHERIT_DEFAULT->WorkloadSelection.INHERITED
    AiRoleSelection.DISABLED->WorkloadSelection.DISABLED
},if(hasExplicitTarget) WorkloadTarget(requireNotNull(providerId),requireNotNull(modelId)) else null)

/** Parsing is advisory; only canonical dispatch can authorize an action. */
enum class ProvisionalIntentState { PARTIAL, DETERMINISTIC, AMBIGUOUS, ASSISTANT }
data class WorkloadIntent(val state: ProvisionalIntentState,val confidence: Double,
    val workload: LocalWorkload,val canonicalIntentId: String?=null,val actionAuthorized: Boolean=false)
fun interface OptionalIntentClassifier { suspend fun classify(finalText: String): WorkloadIntent? }
