package com.halo.runtime.kernel.resource

import com.halo.sdk.runtime.resource.ResourceAccess
import com.halo.sdk.runtime.resource.ResourceAcquireResult
import com.halo.sdk.runtime.resource.ResourceClaim
import com.halo.sdk.runtime.resource.ResourceConflict
import com.halo.sdk.runtime.resource.ResourceLease
import com.halo.sdk.runtime.resource.ResourceLeaseId
import com.halo.sdk.runtime.resource.ResourceLeaseManager
import com.halo.sdk.runtime.resource.ResourceLeaseRecord
import com.halo.sdk.runtime.resource.ResourceLeaseRequest
import com.halo.sdk.runtime.resource.ResourceLeaseSnapshot
import com.halo.sdk.runtime.resource.ResourcePreemptionPolicy
import com.halo.sdk.runtime.resource.ResourceWaitPolicy
import com.halo.sdk.runtime.resource.ResourceWaitRecord
import com.halo.sdk.runtime.resource.ResourceYieldDecision
import com.halo.sdk.runtime.resource.ResourceYieldHandler
import com.halo.sdk.runtime.resource.ResourceYieldRequest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide semantic resource arbitration.
 *
 * State mutation uses one short non-suspending monitor. No callback or coroutine
 * suspension occurs while the monitor is held.
 */
class DefaultResourceLeaseManager : ResourceLeaseManager {

    private val stateLock = Any()
    private val activeLeases = mutableMapOf<ResourceLeaseId, ActiveLease>()
    private val waitingRequests = mutableMapOf<String, ResourceWaitRecord>()
    private val stateEpoch = MutableStateFlow(0L)

    private val mutableSnapshot = MutableStateFlow(ResourceLeaseSnapshot(emptyList(), emptyList()))
    override val snapshotState: StateFlow<ResourceLeaseSnapshot> = mutableSnapshot.asStateFlow()

    private inner class ActiveLease(
        override val leaseId: ResourceLeaseId,
        override val request: ResourceLeaseRequest,
        val ownerYieldHandler: ResourceYieldHandler?,
        override val acquiredAtMs: Long = System.currentTimeMillis(),
    ) : ResourceLease {
        private val closed = AtomicBoolean(false)

        override val isReleased: Boolean
            get() = closed.get()

        override fun release() {
            if (closed.compareAndSet(false, true)) {
                this@DefaultResourceLeaseManager.release(leaseId)
            }
        }
    }

    override fun snapshot(): ResourceLeaseSnapshot = mutableSnapshot.value

    override fun tryAcquire(
        request: ResourceLeaseRequest,
        ownerYieldHandler: ResourceYieldHandler?,
    ): ResourceAcquireResult = synchronized(stateLock) {
        val conflicts = findConflictsLocked(request)
        if (conflicts.isNotEmpty()) {
            return@synchronized ResourceAcquireResult.Busy(conflicts)
        }

        val lease = ActiveLease(
            leaseId = ResourceLeaseId(UUID.randomUUID().toString()),
            request = request,
            ownerYieldHandler = ownerYieldHandler,
        )
        activeLeases[lease.leaseId] = lease
        publishLocked()
        ResourceAcquireResult.Granted(lease)
    }

    override suspend fun acquire(
        request: ResourceLeaseRequest,
        ownerYieldHandler: ResourceYieldHandler?,
    ): ResourceAcquireResult {
        var latest = tryAcquire(request, ownerYieldHandler)
        if (latest is ResourceAcquireResult.Granted) return latest

        if (request.preemption == ResourcePreemptionPolicy.COOPERATIVE) {
            val prompted = mutableSetOf<ResourceLeaseId>()
            val conflicts = (latest as? ResourceAcquireResult.Busy)?.conflicts.orEmpty()
                .filter { it.priority < request.priority }

            for (conflict in conflicts) {
                if (!prompted.add(conflict.heldLeaseId)) continue

                val ownerHandler = synchronized(stateLock) {
                    activeLeases[conflict.heldLeaseId]?.ownerYieldHandler
                } ?: continue

                val epochBefore = stateEpoch.value
                val decision = ownerHandler.requestYield(
                    ResourceYieldRequest(
                        requestedClaim = conflict.requested,
                        requestingHolder = request.holder,
                        requestingPriority = request.priority,
                        heldLeaseId = conflict.heldLeaseId,
                        heldClaim = synchronized(stateLock) {
                            activeLeases[conflict.heldLeaseId]
                                ?.request
                                ?.claims
                                ?.firstOrNull { held -> claimsConflict(conflict.requested, held) }
                        } ?: conflict.requested,
                    )
                )

                if (decision == ResourceYieldDecision.RELEASING) {
                    // The owner performs real cleanup then explicitly releases.
                    // Wait outside stateLock for that evidence, bounded.
                    withTimeoutOrNull(500L) {
                        stateEpoch.first { it != epochBefore }
                    }
                }
            }

            latest = tryAcquire(request, ownerYieldHandler)
            if (latest is ResourceAcquireResult.Granted) return latest
        }

        val timeoutMs = when (val policy = request.waitPolicy) {
            ResourceWaitPolicy.FailFast -> return latest
            is ResourceWaitPolicy.Wait -> policy.timeoutMs
            ResourceWaitPolicy.SuspendRun -> 5_000L
        }

        val waitRecord = ResourceWaitRecord(
            requestId = request.requestId,
            holder = request.holder,
            claims = request.claims,
            priority = request.priority,
            requestedAtMs = System.currentTimeMillis(),
        )

        synchronized(stateLock) {
            waitingRequests[request.requestId] = waitRecord
            publishLocked()
        }

        return try {
            val granted = withTimeoutOrNull(timeoutMs) {
                while (true) {
                    val observedEpoch = stateEpoch.value
                    val attempt = tryAcquire(request, ownerYieldHandler)
                    if (attempt is ResourceAcquireResult.Granted) return@withTimeoutOrNull attempt
                    stateEpoch.first { it != observedEpoch }
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }

            granted ?: ResourceAcquireResult.TimedOut(
                synchronized(stateLock) { findConflictsLocked(request) }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            synchronized(stateLock) {
                waitingRequests.remove(request.requestId)
                publishLocked()
            }
        }
    }

    override fun release(leaseId: ResourceLeaseId) {
        synchronized(stateLock) {
            if (activeLeases.remove(leaseId) != null) {
                publishLocked()
            }
        }
    }

    private fun findConflictsLocked(request: ResourceLeaseRequest): List<ResourceConflict> {
        val conflicts = mutableListOf<ResourceConflict>()
        for (active in activeLeases.values) {
            for (requestedClaim in request.claims) {
                for (heldClaim in active.request.claims) {
                    if (!claimsConflict(requestedClaim, heldClaim)) continue
                    conflicts += ResourceConflict(
                        requested = requestedClaim,
                        heldBy = active.request.holder,
                        heldLeaseId = active.leaseId,
                        heldSinceMs = active.acquiredAtMs,
                        priority = active.request.priority,
                    )
                }
            }
        }
        return conflicts
    }

    private fun claimsConflict(a: ResourceClaim, b: ResourceClaim): Boolean =
        a.key == b.key &&
            (a.access == ResourceAccess.EXCLUSIVE || b.access == ResourceAccess.EXCLUSIVE)

    private fun publishLocked() {
        mutableSnapshot.value = ResourceLeaseSnapshot(
            active = activeLeases.values
                .sortedWith(compareBy({ it.acquiredAtMs }, { it.leaseId.value }))
                .map {
                    ResourceLeaseRecord(
                        leaseId = it.leaseId,
                        holder = it.request.holder,
                        claims = it.request.claims,
                        priority = it.request.priority,
                        acquiredAtMs = it.acquiredAtMs,
                    )
                },
            waiting = waitingRequests.values
                .sortedWith(
                    compareByDescending<ResourceWaitRecord> { it.priority.ordinal }
                        .thenBy { it.requestedAtMs }
                        .thenBy { it.requestId }
                ),
        )
        stateEpoch.value = stateEpoch.value + 1L
    }
}
