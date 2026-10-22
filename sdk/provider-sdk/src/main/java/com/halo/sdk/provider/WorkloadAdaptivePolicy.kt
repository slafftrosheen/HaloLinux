package com.halo.sdk.provider

/** Missing data sorts last; no guessed throughput or marker-only quality score. */
object WorkloadAdaptivePolicy {
    fun rank(request: WorkloadRequest,candidates: List<WorkloadCandidate>): List<WorkloadCandidate> = candidates
        .filter { candidate -> candidate.local && candidate.installed && candidate.nativeReadable &&
            candidate.memoryEstimateBytes>0 && candidate.memoryEstimateBytes<=request.availableBytes &&
            candidate.proofs.any { p -> current(p,candidate,request) } }
        .sortedWith(compareBy<WorkloadCandidate> { candidate -> candidate.proofs.filter { current(it,candidate,request) }
            .mapNotNull { if(candidate.resident) it.warmMs else it.coldMs }.minOrNull() ?: Long.MAX_VALUE }
            .thenBy { it.memoryEstimateBytes }.thenBy { it.target.modelId })
    private fun current(p: WorkloadTaskProof,c: WorkloadCandidate,r: WorkloadRequest)=p.target==c.target && p.workload==r.workload &&
        p.environment==r.environment && p.backend in c.supportedBackends && p.correct && p.stable
}
