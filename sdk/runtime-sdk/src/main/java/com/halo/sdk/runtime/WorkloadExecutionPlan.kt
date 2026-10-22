package com.halo.sdk.runtime

import com.halo.sdk.runtime.resource.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class WorkloadStageState { PENDING, READY, RUNNING, COMPLETED, FAILED, CANCELLED, SKIPPED }
class WorkloadStageSkippedException(val reasonCode: String): Exception(reasonCode) {
    init { require(reasonCode.matches(Regex("[A-Z0-9_]{1,80}"))) }
}
data class WorkloadStageSpec(val id: String,val workload: String,val dependencies: Set<String> = emptySet(),
    val claims: Set<ResourceClaim> = emptySet(),val providerOwnsLease: Boolean=true,
    val priority: ResourcePriority=ResourcePriority.INTERACTIVE,val deadlineAfterMs: Long=120_000,
    val speculative: Boolean=false) {
    init { require(id.length in 1..128 && workload.length in 1..64 && deadlineAfterMs in 1..600_000) }
}
data class WorkloadExecutionPlan(val interactionId: String,val stages: List<WorkloadStageSpec>,val id: String=UUID.randomUUID().toString()) {
    init {
        require(interactionId.length in 1..128 && stages.size in 1..32 && stages.map { it.id }.distinct().size==stages.size)
        val ids=stages.map { it.id }.toSet(); require(stages.all { it.dependencies.all(ids::contains) })
        val visited=mutableSetOf<String>()
        while(visited.size<stages.size) {
            val ready=stages.filter { it.id !in visited && visited.containsAll(it.dependencies) }
            require(ready.isNotEmpty()) { "CYCLIC_PLAN" }; visited+=ready.map { it.id }
        }
    }
}
data class WorkloadStageEvent(val interactionId: String,val planId: String,val stageId: String,val workload: String,
    val state: WorkloadStageState,val reasonCode: String?=null,val waitMs: Long?=null,val durationMs: Long?=null,
    val providerId: String?=null,val modelId: String?=null,val effectiveBackend: String?=null,
    override val id: String=UUID.randomUUID().toString(),override val timestamp: Long=System.currentTimeMillis()): HaloEvent
object WorkloadStageTimeline {
    private val mutable=MutableStateFlow<List<WorkloadStageEvent>>(emptyList())
    val events=mutable.asStateFlow()
    @Synchronized fun record(event: WorkloadStageEvent) { mutable.value=(mutable.value+event).takeLast(256) }
}
class WorkloadStageContext internal constructor(val interactionId: String,val stageId: String,
    private val identity: (String,String,String?)->Unit) {
    fun observedExecution(providerId: String,modelId: String,effectiveBackend: String?) {
        require(providerId.length<=160 && modelId.length<=200 && (effectiveBackend?.length ?: 0)<=32)
        identity(providerId,modelId,effectiveBackend)
    }
}
fun interface WorkloadStageLeaseGate { suspend fun acquire(stage: WorkloadStageSpec,interactionId: String): ResourceLease? }

/** Provider/JNI owners retain their own lease; scheduler acquires only explicitly scheduler-owned claims. */
class ExistingResourceStageGate(private val manager: ResourceLeaseManager): WorkloadStageLeaseGate {
    override suspend fun acquire(stage: WorkloadStageSpec,interactionId: String): ResourceLease? {
        if(stage.providerOwnsLease || stage.claims.isEmpty()) return null
        return when(val result=manager.acquire(ResourceLeaseRequest("stage:$interactionId:${stage.id}",
            ResourceHolder("stage:${stage.id}",interactionId=interactionId,kind=ResourceHolderKind.INTERACTION),
            stage.claims,stage.priority,ResourceWaitPolicy.Wait(stage.deadlineAfterMs)))) {
            is ResourceAcquireResult.Granted -> result.lease
            else -> error("STAGE_RESOURCE_UNAVAILABLE")
        }
    }
}

/** Caller-owned structured children; no persistent jobs, replay authority or unbounded pending queue. */
class WorkloadPlanScheduler(private val concurrency: Int=2, maxPlans: Int=2,
    private val gate: WorkloadStageLeaseGate=WorkloadStageLeaseGate { stage,_ ->
        check(stage.providerOwnsLease || stage.claims.isEmpty()) { "RESOURCE_GATE_REQUIRED" }; null
    },private val eventSink: suspend (WorkloadStageEvent)->Unit = {}) {
    private val plans=Semaphore(maxPlans)
    init { require(concurrency in 1..4 && maxPlans in 1..8) }
    suspend fun execute(plan: WorkloadExecutionPlan,actions: Map<String,suspend (WorkloadStageContext)->Unit>): Map<String,WorkloadStageState> {
        require(actions.keys==plan.stages.map { it.id }.toSet())
        check(plans.tryAcquire()) { "WORKLOAD_QUEUE_FULL" }
        val submitted=System.nanoTime()
        val states=plan.stages.associate { it.id to WorkloadStageState.PENDING }.toMutableMap()
        suspend fun event(stage: WorkloadStageSpec,state: WorkloadStageState,reason: String?=null,
            wait: Long?=null,duration: Long?=null,provider: String?=null,model: String?=null,backend: String?=null) {
            val row=WorkloadStageEvent(plan.interactionId,plan.id,stage.id,stage.workload,state,reason,wait,duration,provider,model,backend)
            WorkloadStageTimeline.record(row)
            try { eventSink(row) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // An observational sink cannot make a model/capture stage fail.
                // The bounded in-process timeline already owns the evidence.
            }
        }
        try { return coroutineScope {
            val running=mutableMapOf<String,Deferred<WorkloadStageState>>()
            plan.stages.forEach { event(it,WorkloadStageState.PENDING) }
            while(states.values.any { it in setOf(WorkloadStageState.PENDING,WorkloadStageState.RUNNING) }) {
                currentCoroutineContext().ensureActive()
                for(stage in plan.stages.filter { states[it.id]==WorkloadStageState.PENDING }) {
                    if(stage.dependencies.any { states[it] in setOf(WorkloadStageState.FAILED,WorkloadStageState.CANCELLED,WorkloadStageState.SKIPPED) }) {
                        states[stage.id]=WorkloadStageState.SKIPPED; event(stage,WorkloadStageState.SKIPPED,"DEPENDENCY_NOT_COMPLETED")
                    }
                }
                val ready=plan.stages.filter { states[it.id]==WorkloadStageState.PENDING && it.dependencies.all { dependency -> states[dependency]==WorkloadStageState.COMPLETED } }
                    .sortedWith(compareByDescending<WorkloadStageSpec> { it.priority.ordinal }.thenBy { it.speculative })
                    .take((concurrency-running.size).coerceAtLeast(0))
                for(stage in ready) {
                    event(stage,WorkloadStageState.READY); states[stage.id]=WorkloadStageState.RUNNING
                    running[stage.id]=async {
                        val started=System.nanoTime(); var lease: ResourceLease?=null
                        var provider: String?=null; var model: String?=null; var backend: String?=null
                        var outcome=WorkloadStageState.FAILED; var reason: String?=null
                        try {
                            val remaining=stage.deadlineAfterMs-(started-submitted)/1_000_000
                            if(remaining<=0) { reason="DEADLINE_EXCEEDED"; return@async WorkloadStageState.FAILED }
                            withTimeout(remaining) {
                                lease=gate.acquire(stage,plan.interactionId)
                                event(stage,WorkloadStageState.RUNNING,wait=(System.nanoTime()-submitted)/1_000_000)
                                actions.getValue(stage.id)(WorkloadStageContext(plan.interactionId,stage.id) { p,m,b -> provider=p; model=m; backend=b })
                            }
                            outcome=WorkloadStageState.COMPLETED
                        } catch(skipped: WorkloadStageSkippedException) { outcome=WorkloadStageState.SKIPPED; reason=skipped.reasonCode }
                        catch(timeout: TimeoutCancellationException) { reason="DEADLINE_EXCEEDED" }
                        catch(cancelled: CancellationException) {
                            outcome=WorkloadStageState.CANCELLED
                            reason=if(cancelled.message=="REQUIRED_STAGE_FAILED") "REQUIRED_STAGE_FAILED" else "OWNER_CANCELLED"
                            throw cancelled
                        }
                        catch(error: Exception) { reason="STAGE_${error.javaClass.simpleName.take(64)}" }
                        finally { withContext(NonCancellable) {
                            lease?.release()
                            event(stage,outcome,reason,(started-submitted)/1_000_000,(System.nanoTime()-started)/1_000_000,provider,model,backend)
                        } }
                        outcome
                    }
                }
                if(running.isEmpty()) break
                // onAwait rethrows a cancelled *child* even when this interaction is
                // still active. An optional speculative load may be preempted by
                // memory pressure without cancelling mandatory context acquisition.
                // onJoin reports that child's cancellation as a stage outcome instead.
                val completed=select<Pair<String,WorkloadStageState>> {
                    running.forEach { (id,job) ->
                        job.onJoin {
                            id to if (job.isCancelled) WorkloadStageState.CANCELLED else job.await()
                        }
                    }
                }
                currentCoroutineContext().ensureActive() // owner cancellation is still authoritative
                running.remove(completed.first); states[completed.first]=completed.second
                val mandatory = plan.stages.first { it.id == completed.first }
                if(!mandatory.speculative && completed.second != WorkloadStageState.COMPLETED) {
                    // Context/source failure is an authoritative stop signal. Speculative
                    // warmup must never hold resources for its full model-load timeout.
                    // Join cancelled children so provider/JNI owners can release leases.
                    running.values.filter { !it.isCompleted }.forEach {
                        it.cancel(CancellationException("REQUIRED_STAGE_FAILED"))
                    }
                    for ((id, job) in running) {
                        job.join()
                        states[id] = if (job.isCancelled) WorkloadStageState.CANCELLED else job.await()
                    }
                    running.clear()
                    for (stage in plan.stages.filter { states[it.id]==WorkloadStageState.PENDING }) {
                        states[stage.id] = WorkloadStageState.SKIPPED
                        event(stage, WorkloadStageState.SKIPPED, "REQUIRED_STAGE_FAILED")
                    }
                    break
                }
            }
            states.toMap()
        } } finally {
            try { withContext(NonCancellable) {
                plan.stages.filter { states[it.id] in setOf(WorkloadStageState.PENDING,WorkloadStageState.RUNNING) }.forEach {
                    event(it,WorkloadStageState.CANCELLED,"PLAN_CANCELLED")
                }
            } } finally { plans.release() }
        }
    }
    suspend fun <T> stage(interactionId: String,spec: WorkloadStageSpec,body: suspend (WorkloadStageContext)->T): T {
        var result: Result<T>?=null
        val states=execute(WorkloadExecutionPlan(interactionId,listOf(spec)),mapOf(spec.id to { context ->
            try { result=Result.success(body(context)) }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(error: Exception) { result=Result.failure(error); throw error }
            Unit
        }))
        if(states[spec.id]!=WorkloadStageState.COMPLETED) {
            result?.exceptionOrNull()?.let { throw it }
            error("WORKLOAD_STAGE_NOT_COMPLETED")
        }
        return requireNotNull(result).getOrThrow()
    }
}
