package com.halo.sdk.runtime.resource

import kotlinx.coroutines.flow.StateFlow

interface ResourceLease {
    val leaseId: ResourceLeaseId
    val request: ResourceLeaseRequest
    val acquiredAtMs: Long
    val isReleased: Boolean

    /**
     * Releases Runtime ownership only. Physical/platform cleanup must finish
     * before the owner calls this method.
     */
    fun release()
}

interface ResourceLeaseManager {

    val snapshotState: StateFlow<ResourceLeaseSnapshot>

    /**
     * [ownerYieldHandler] belongs to the lease being requested. If the lease is
     * later blocking a higher-priority request, the manager may ask THIS owner
     * to yield. The requester never supplies cleanup logic for another owner.
     */
    fun tryAcquire(
        request: ResourceLeaseRequest,
        ownerYieldHandler: ResourceYieldHandler? = null,
    ): ResourceAcquireResult

    suspend fun acquire(
        request: ResourceLeaseRequest,
        ownerYieldHandler: ResourceYieldHandler? = null,
    ): ResourceAcquireResult

    fun snapshot(): ResourceLeaseSnapshot

    fun release(leaseId: ResourceLeaseId)
}

suspend fun <T> ResourceLeaseManager.withLease(
    request: ResourceLeaseRequest,
    ownerYieldHandler: ResourceYieldHandler? = null,
    block: suspend (ResourceLease) -> T,
): ResourceExecutionResult<T> {
    return when (val acquireResult = acquire(request, ownerYieldHandler)) {
        is ResourceAcquireResult.Granted -> {
            val lease = acquireResult.lease
            try {
                ResourceExecutionResult.Success(block(lease), lease)
            } catch (t: Throwable) {
                ResourceExecutionResult.Error(t)
            } finally {
                lease.release()
            }
        }
        else -> ResourceExecutionResult.FailedToAcquire(acquireResult)
    }
}
