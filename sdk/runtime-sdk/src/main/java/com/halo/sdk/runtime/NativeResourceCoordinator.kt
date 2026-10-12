package com.halo.sdk.runtime

import com.halo.sdk.runtime.resource.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native resources that should not be resident concurrently on constrained devices. */
enum class RuntimeWorkloadClass {
    ASR,
    TEXT_FAST,
    TEXT_CHAT,
    TEXT_REASONING,
    AGENTIC,
    VISION,
    TTS
}

enum class ResidencyPreference {
    ON_DEMAND,
    WARM_EJECTABLE,
    PINNED
}

data class WorkloadResourcePolicy(
    val workloadClass: RuntimeWorkloadClass,
    val memoryEstimateBytes: Long = 0,
    val exclusive: Boolean = true,
    val residencyPreference: ResidencyPreference = ResidencyPreference.ON_DEMAND,
    val priority: Int = 0,
    val idleGraceMillis: Long? = null
)

enum class NativeResourceKind {
    HEAVY_LLM,
    HEAVY_ASR,
    HEAVY_TTS
}

enum class NativeResourceState {
    RELEASED,
    ACQUIRING,
    LOADING,
    RESIDENT,
    IN_USE
}

enum class NativeNextUseHint {
    EXPECT_ASR,
    EXPECT_LLM,
    NONE
}

data class NativeResourceSnapshot(
    val ownerId: String,
    val kind: NativeResourceKind,
    val state: NativeResourceState,
    val reason: String? = null,
    /**
     * MINING-P0-001 residency is not execution.
     *
     * [NativeResourceState.RESIDENT] means the model/native runtime stays loaded or cached and
     * [executionLeaseHeld] is false. [NativeResourceState.IN_USE] means an active compute lease is
     * held. Only simultaneous ACTIVE EXECUTION should conflict when the fabric requires
     * exclusivity; ASR may stay resident while LiteRT runs and LiteRT may stay resident while
     * Sherpa decodes.
     */
    val executionLeaseHeld: Boolean = false,
    val executionResourceId: String? = null,
    val executionWaitMs: Long? = null,
    val executionAcquiredAtMs: Long? = null,
    val lastTransitionAtMs: Long = System.currentTimeMillis(),
    /** Optional correlation id for the operation that caused the last transition. */
    val operationId: String? = null,
) {
    /** Alias kept explicit so readers never mistake [state] for an execution claim. */
    val lifecycleState: NativeResourceState get() = state

    /** True only when an execution lease is genuinely held right now. */
    val executing: Boolean get() = state == NativeResourceState.IN_USE && executionLeaseHeld
}

/** Owner of a native resource lease. */
interface NativeResourceOwner {
    val nativeResourceKind: NativeResourceKind
    val nativeResourceId: String
        get() = nativeResourceKind.name + ":" + (this::class.qualifiedName ?: this::class.simpleName ?: "unknown")
    val defaultResourcePolicy: WorkloadResourcePolicy
        get() = WorkloadResourcePolicy(RuntimeWorkloadClass.TEXT_CHAT, exclusive = true, residencyPreference = ResidencyPreference.WARM_EJECTABLE)
    /** Cancel obsolete active compute and await its cleanup; keep resident caches when safe. */
    suspend fun preemptForVoiceInput(): Boolean = false
    suspend fun releaseIfIdle(): Boolean
}

enum class NativeMemoryPressure { NORMAL, LOW, CRITICAL }
data class NativeResourceBudget(
    val totalBytes: Long = 0,
    val availableBytes: Long = 0,
    val pressure: NativeMemoryPressure = NativeMemoryPressure.NORMAL,
    val idleGraceMillis: Long = 30_000,
    val allowConcurrentHeavyweights: Boolean = true
)

/** Process-scoped arbitration and observability for heavyweight native resources. */
class NativeResourceCoordinator(
    private val resourceLeaseManager: ResourceLeaseManager? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /** Host-owned live budget probe, invoked before resource admission and outside the coordinator lock. */
    @Volatile var budgetReevaluation: (suspend () -> Unit)? = null

    private val mutex = Mutex()
    private val expiry = mutableMapOf<NativeResourceOwner, Job>()
    private val activeLeases = mutableMapOf<NativeResourceOwner, ResourceLease>()

    private val _budget = MutableStateFlow(NativeResourceBudget())
    val budget: StateFlow<NativeResourceBudget> = _budget.asStateFlow()
    private val _nextUseHint = MutableStateFlow(NativeNextUseHint.NONE)
    val nextUseHint: StateFlow<NativeNextUseHint> = _nextUseHint.asStateFlow()

    suspend fun hint(next: NativeNextUseHint) = mutex.withLock {
        _nextUseHint.value = next
        val wanted = when (next) {
            NativeNextUseHint.EXPECT_ASR -> NativeResourceKind.HEAVY_ASR
            NativeNextUseHint.EXPECT_LLM -> NativeResourceKind.HEAVY_LLM
            NativeNextUseHint.NONE -> null
        }
        if (wanted != null && (next != NativeNextUseHint.EXPECT_LLM || _budget.value.pressure == NativeMemoryPressure.NORMAL)) {
            val warmOwners = active.filter { it.nativeResourceKind == wanted &&
                (next != NativeNextUseHint.EXPECT_LLM ||
                    (it.defaultResourcePolicy.residencyPreference == ResidencyPreference.WARM_EJECTABLE && executionFacts[it]?.leaseHeld != true)) }
            for (owner in warmOwners) {
                expiry[owner]?.cancel()
                val grace = owner.defaultResourcePolicy.idleGraceMillis
                    ?: if (_budget.value.pressure == NativeMemoryPressure.NORMAL) _budget.value.idleGraceMillis else 0L
                expiry[owner] = scope.launch {
                    delay(if (_budget.value.pressure == NativeMemoryPressure.NORMAL) grace else 0L)
                    while (isActive) {
                        val released = mutex.withLock {
                            if (owner !in active) true else if (owner.releaseIfIdle()) {
                                active -= owner
                                expiry.remove(owner)?.cancel()
                                activeLeases.remove(owner)?.release()
                                clearExecutionFacts(owner)
                                publish(owner, NativeResourceState.RELEASED, "idle grace elapsed")
                                true
                            } else false
                        }
                        if (released) break
                        delay(500)
                    }
                }
            }
        }
    }

    suspend fun updateBudget(value: NativeResourceBudget) {
        _budget.value = value
        if (value.pressure != NativeMemoryPressure.NORMAL) releaseIdle("memory_pressure")
    }

    suspend fun releaseIdle(reason: String) = mutex.withLock {
        // Evict based on residency preference if memory pressure is critical
        val pressure = _budget.value.pressure
        val evictable = active.filter { owner ->
            val pref = owner.defaultResourcePolicy.residencyPreference
            pref == ResidencyPreference.ON_DEMAND || 
            (pref == ResidencyPreference.WARM_EJECTABLE && pressure != NativeMemoryPressure.NORMAL)
        }.sortedBy { it.defaultResourcePolicy.priority } // lower priority first

        val released = evictable.filter { it.releaseIfIdle() }
        released.forEach { owner ->
            active -= owner
            expiry.remove(owner)?.cancel()
            activeLeases.remove(owner)?.release()
            clearExecutionFacts(owner)
            publish(owner, NativeResourceState.RELEASED, reason)
        }
    }

    private val owners = LinkedHashSet<NativeResourceOwner>()
    private val active = LinkedHashSet<NativeResourceOwner>()

    /** Execution facts are tracked separately from residency so the two can never be conflated. */
    private data class ExecutionFacts(
        val leaseHeld: Boolean,
        val resourceId: String?,
        val waitMs: Long?,
        val acquiredAtMs: Long?,
    )

    private val executionFacts = mutableMapOf<NativeResourceOwner, ExecutionFacts>()
    private val lastTransitionAtMs = mutableMapOf<NativeResourceOwner, Long>()
    private val lastOperationId = mutableMapOf<NativeResourceOwner, String?>()
    private val _snapshot = MutableStateFlow<List<NativeResourceSnapshot>>(emptyList())
    val snapshot: StateFlow<List<NativeResourceSnapshot>> = _snapshot.asStateFlow()

    suspend fun register(owner: NativeResourceOwner) = mutex.withLock {
        owners += owner
        publish(owner, NativeResourceState.RELEASED, "registered")
    }

    suspend fun unregister(owner: NativeResourceOwner) = mutex.withLock {
        expiry.remove(owner)?.cancel()
        active -= owner
        owners -= owner
        activeLeases.remove(owner)?.release()
        remove(owner)
    }

    /** Eviction path also drops execution facts so a stale lease claim can never be observed. */
    private fun clearExecutionFacts(owner: NativeResourceOwner) {
        executionFacts[owner] = ExecutionFacts(
            leaseHeld = false,
            resourceId = executionFacts[owner]?.resourceId,
            waitMs = executionFacts[owner]?.waitMs,
            acquiredAtMs = null,
        )
    }

    suspend fun acquire(owner: NativeResourceOwner, operationId: String? = null, diagnostic: Boolean = false): Boolean {
        budgetReevaluation?.invoke()
        val start = System.currentTimeMillis()
        if (!diagnostic && owner.nativeResourceKind == NativeResourceKind.HEAVY_ASR) {
            // Never call an owner's cleanup under the coordinator mutex: its finally block releases the lease.
            val obsolete = mutex.withLock { active.filter { it !== owner && it.nativeResourceKind == NativeResourceKind.HEAVY_LLM && executionFacts[it]?.leaseHeld == true } }
            for (conflict in obsolete) {
                println("NATIVE_EXECUTION_PREEMPT owner=${conflict.nativeResourceId} requester=${owner.nativeResourceId} reason=VOICE_ASR")
                withTimeoutOrNull(1000) { conflict.preemptForVoiceInput() }
            }
        }
        val resourceId = when (owner.nativeResourceKind) {
            NativeResourceKind.HEAVY_LLM -> HaloResourceIds.ComputeInference
            NativeResourceKind.HEAVY_ASR -> HaloResourceIds.ComputeAsr
            NativeResourceKind.HEAVY_TTS -> HaloResourceIds.ComputeTts
        }
        val claims = mutableSetOf(
            ResourceClaim(ResourceKey(resourceId, ResourceEndpointId("host:primary")), ResourceAccess.EXCLUSIVE)
        )
        if (_budget.value.pressure != NativeMemoryPressure.NORMAL || !_budget.value.allowConcurrentHeavyweights) {
            claims.add(ResourceClaim(ResourceKey(HaloResourceIds.ComputeHeavy, ResourceEndpointId("host:primary")), ResourceAccess.EXCLUSIVE))
        }

        println(
            "NATIVE_EXECUTION_ACQUIRE owner=${owner.nativeResourceId} kind=${owner.nativeResourceKind.name} " +
                "resource=$resourceId operation=${operationId ?: "none"} reason=acquire"
        )

        if (resourceLeaseManager != null) {
            val req = ResourceLeaseRequest(
                requestId = "native:${owner.nativeResourceId}",
                holder = ResourceHolder(id = owner.nativeResourceId, kind = ResourceHolderKind.PROVIDER),
                claims = claims,
                priority = if (diagnostic) ResourcePriority.MAINTENANCE else if (owner.nativeResourceKind == NativeResourceKind.HEAVY_ASR) ResourcePriority.INTERACTIVE_INPUT else ResourcePriority.INTERACTIVE,
                waitPolicy = if (diagnostic) ResourceWaitPolicy.FailFast else ResourceWaitPolicy.Wait(1000L),
                preemption = if (diagnostic) ResourcePreemptionPolicy.NEVER else ResourcePreemptionPolicy.COOPERATIVE,
            )
            when (val acq = resourceLeaseManager.acquire(req)) {
                is ResourceAcquireResult.Granted -> {
                    activeLeases[owner] = acq.lease
                }
                else -> {
                    mutex.withLock {
                        executionFacts[owner] = ExecutionFacts(
                            leaseHeld = false,
                            resourceId = resourceId.value,
                            waitMs = System.currentTimeMillis() - start,
                            acquiredAtMs = null,
                        )
                        publish(owner, NativeResourceState.RELEASED, "generic resource busy: $acq")
                    }
                    return false
                }
            }
        }

        val acquired = withTimeoutOrNull(1000) {
            while (true) {
                val success = mutex.withLock {
                    owners += owner
                    expiry.remove(owner)?.cancel()
                    val conflicts = active.filter { it !== owner &&
                        executionFacts[it]?.leaseHeld == true &&
                        (it.nativeResourceKind == owner.nativeResourceKind || !_budget.value.allowConcurrentHeavyweights ||
                            _budget.value.pressure != NativeMemoryPressure.NORMAL) }
                    var blocked = false
                    for (conflict in conflicts) {
                        if (!diagnostic && conflict.releaseIfIdle()) {
                            active -= conflict
                            expiry.remove(conflict)?.cancel()
                            activeLeases.remove(conflict)?.release()
                            clearExecutionFacts(conflict)
                            publish(conflict, NativeResourceState.RELEASED, "yielded to ${owner.nativeResourceKind}")
                        } else blocked = true
                    }
                    if (blocked) {
                        publish(owner, NativeResourceState.ACQUIRING, "busy")
                        false
                    } else {
                        active += owner
                        publish(owner, NativeResourceState.RESIDENT, "lease acquired")
                        true
                    }
                }
                if (success) return@withTimeoutOrNull true
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false

        val duration = System.currentTimeMillis() - start
        if (acquired) {
            mutex.withLock {
                executionFacts[owner] = ExecutionFacts(
                    leaseHeld = true,
                    resourceId = resourceId.value,
                    waitMs = duration,
                    acquiredAtMs = System.currentTimeMillis(),
                )
                lastOperationId[owner] = operationId
            }
            println(
                "NATIVE_EXECUTION_GRANTED owner=${owner.nativeResourceId} kind=${owner.nativeResourceKind.name} " +
                    "resource=$resourceId operation=${operationId ?: "none"} waitMs=$duration state=GRANTED"
            )
        } else {
            mutex.withLock {
                activeLeases.remove(owner)?.release()
                executionFacts[owner] = ExecutionFacts(
                    leaseHeld = false,
                    resourceId = resourceId.value,
                    waitMs = duration,
                    acquiredAtMs = null,
                )
                publish(owner, NativeResourceState.RELEASED, "busy")
            }
        }
        return acquired
    }

    /** Update provider lifecycle state. Active compute lease is released when model is merely RESIDENT or RELEASED. */
    suspend fun mark(
        owner: NativeResourceOwner,
        state: NativeResourceState,
        reason: String,
        operationId: String? = null,
    ) = mutex.withLock {
        owners += owner
        lastOperationId[owner] = operationId ?: lastOperationId[owner]
        expiry.remove(owner)?.cancel()

        // When execution finishes and the provider returns to RESIDENT or RELEASED, release the
        // active execution lease immediately: residency survives, the compute claim does not.
        if (state == NativeResourceState.RESIDENT || state == NativeResourceState.RELEASED) {
            val released = activeLeases.remove(owner)?.release()
            val previous = executionFacts[owner]
            executionFacts[owner] = ExecutionFacts(
                leaseHeld = false,
                resourceId = previous?.resourceId,
                waitMs = previous?.waitMs,
                acquiredAtMs = null,
            )
            if (released != null || previous?.leaseHeld == true) {
                println(
                    "NATIVE_EXECUTION_RELEASE owner=${owner.nativeResourceId} kind=${owner.nativeResourceKind.name} " +
                        "resource=${previous?.resourceId ?: "none"} " +
                        "operation=${lastOperationId[owner] ?: "none"} state=${state.name} reason=$reason"
                )
            }
        }

        // Single publish per transition: facts are already correct for the new state.
        publish(owner, state, reason)

        if (state == NativeResourceState.RESIDENT) {
            val grace = owner.defaultResourcePolicy.idleGraceMillis
                ?: if (_budget.value.pressure == NativeMemoryPressure.NORMAL) _budget.value.idleGraceMillis else 0L
            expiry[owner] = scope.launch {
                delay(if (_budget.value.pressure == NativeMemoryPressure.NORMAL) grace else 0L)
                while (isActive) {
                    val released = mutex.withLock {
                        if (owner !in active) true else if (owner.releaseIfIdle()) {
                            active -= owner
                            expiry.remove(owner)?.cancel()
                            activeLeases.remove(owner)?.release()
                            clearExecutionFacts(owner)
                            publish(owner, NativeResourceState.RELEASED, "idle grace elapsed")
                            true
                        } else false
                    }
                    if (released) break
                    delay(500)
                }
            }
        }
    }

    suspend fun release(owner: NativeResourceOwner) = mutex.withLock {
        expiry.remove(owner)?.cancel()
        active -= owner
        val previous = executionFacts[owner]
        executionFacts[owner] = ExecutionFacts(
            leaseHeld = false,
            resourceId = previous?.resourceId,
            waitMs = previous?.waitMs,
            acquiredAtMs = null,
        )
        activeLeases.remove(owner)?.release()
        if (owners.contains(owner)) publish(owner, NativeResourceState.RELEASED, "released")
        println(
            "NATIVE_EXECUTION_RELEASE owner=${owner.nativeResourceId} kind=${owner.nativeResourceKind.name} " +
                "resource=${previous?.resourceId ?: "none"} " +
                "operation=${lastOperationId[owner] ?: "none"} state=RELEASED reason=released"
        )
    }

    /**
     * Residency-based "active" set. A RESIDENT owner counts as active here because it holds a
     * loaded/cached native runtime. Do NOT use this as proof that compute is executing; use
     * [executingKinds] for that.
     */
    suspend fun activeKinds(): Set<NativeResourceKind> = mutex.withLock {
        active.mapTo(LinkedHashSet()) { it.nativeResourceKind }
    }

    /** Kinds currently holding an active execution lease (IN_USE + lease held). */
    suspend fun executingKinds(): Set<NativeResourceKind> = mutex.withLock {
        active
            .filter { executionFacts[it]?.leaseHeld == true }
            .mapTo(LinkedHashSet()) { it.nativeResourceKind }
    }

    /** True when any owner of [kind] is executing right now. */
    suspend fun isExecuting(kind: NativeResourceKind): Boolean = mutex.withLock {
        active.any { it.nativeResourceKind == kind && executionFacts[it]?.leaseHeld == true }
    }

    /** Content-free execution facts for one owner, or null when the owner is unknown. */
    suspend fun executionSnapshot(owner: NativeResourceOwner): NativeResourceSnapshot? = mutex.withLock {
        _snapshot.value.firstOrNull { it.ownerId == owner.nativeResourceId }
    }

    private fun publish(owner: NativeResourceOwner, state: NativeResourceState, reason: String) {
        val now = System.currentTimeMillis()
        lastTransitionAtMs[owner] = now
        val facts = executionFacts[owner]
        val leaseHeld = facts?.leaseHeld == true
        println(
            "NATIVE_RESIDENCY_CHANGED owner=${owner.nativeResourceId} kind=${owner.nativeResourceKind.name} " +
                "state=${state.name} executionLeaseHeld=$leaseHeld " +
                "resource=${facts?.resourceId ?: "none"} " +
                "operation=${lastOperationId[owner] ?: "none"} reason=$reason"
        )
        val next = _snapshot.value.filterNot { it.ownerId == owner.nativeResourceId } +
            NativeResourceSnapshot(
                ownerId = owner.nativeResourceId,
                kind = owner.nativeResourceKind,
                state = state,
                reason = reason,
                executionLeaseHeld = leaseHeld,
                executionResourceId = facts?.resourceId,
                executionWaitMs = facts?.waitMs,
                executionAcquiredAtMs = facts?.acquiredAtMs,
                lastTransitionAtMs = now,
                operationId = lastOperationId[owner],
            )
        _snapshot.value = next.sortedBy { it.ownerId }
    }

    private fun remove(owner: NativeResourceOwner) {
        executionFacts.remove(owner)
        lastTransitionAtMs.remove(owner)
        lastOperationId.remove(owner)
        _snapshot.value = _snapshot.value.filterNot { it.ownerId == owner.nativeResourceId }
    }
}
