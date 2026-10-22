package com.halo.sdk.provider

/** Pure policy. The host performs admission/initialization through the existing provider owner. */
data class WorkloadPreparationConditions(val enabled: Boolean,val totalRamBytes: Long,val availableRamBytes: Long,
    val memoryNormal: Boolean,val thermalSeverity: Int,val microphoneActive: Boolean,val likelyDirectCommand: Boolean)
data class WorkloadPreparationDecision(val prepare: Boolean,val reasonCode: String)
object WorkloadPreparationPolicy {
    fun decide(candidate: WorkloadCandidate,request: WorkloadRequest,conditions: WorkloadPreparationConditions): WorkloadPreparationDecision {
        fun no(code: String)=WorkloadPreparationDecision(false,code)
        if(candidate.resident) return no("ALREADY_RESIDENT_KEEP")
        if(!conditions.enabled) return no("SPECULATION_DISABLED")
        if(!candidate.local || !candidate.installed || !candidate.nativeReadable) return no("NOT_LOCAL_READY")
        if(conditions.microphoneActive || conditions.likelyDirectCommand) return no("VOICE_OR_DIRECT_COMMAND_FIRST")
        if(!conditions.memoryNormal || conditions.thermalSeverity>=3) return no("PRESSURE_OR_THERMAL")
        if(conditions.totalRamBytes<8L*1024*1024*1024 || candidate.memoryEstimateBytes<=0 ||
            conditions.availableRamBytes<candidate.memoryEstimateBytes*2+1024L*1024*1024) return no("CONSERVATIVE_RAM_LIMIT")
        if(candidate.proofs.none { it.target==candidate.target && it.workload==request.workload && it.environment==request.environment &&
                it.correct && it.stable && it.backend in candidate.supportedBackends }) return no("EXACT_TASK_PROOF_REQUIRED")
        return WorkloadPreparationDecision(true,"ELIGIBLE_PREPARATION")
    }
}
