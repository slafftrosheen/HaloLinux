package com.halo.host.linux

import com.halo.sdk.provider.RemoteDataCategory
import com.halo.sdk.provider.RemoteDispatchBlocked
import com.halo.sdk.provider.RemoteDispatchLease
import com.halo.sdk.provider.RemoteDispatchOutcome
import com.halo.sdk.provider.RemoteDispatchPolicy
import com.halo.sdk.provider.RemoteDispatchRequest
import com.halo.sdk.provider.RemoteOperation

/**
 * Linux's narrow implementation of the canonical dispatch contract. The current
 * host sends only a single text request or an explicit provider-health probe.
 * Endpoint/key configuration alone never grants off-device processing permission.
 * No prompt, key, URL, response body or provider cost is retained in this policy.
 */
class LinuxRemoteDispatchPolicy(
    private val providerId: String,
    private val endpoint: LinuxInferenceEndpoint,
    private val allowRemote: Boolean = false,
) : RemoteDispatchPolicy, AutoCloseable {
    private val lock = Any()
    private var stopped = false
    private var intentRevision = 0L
    private val leases = mutableSetOf<Lease>()

    override val revision: Long get() = synchronized(lock) { intentRevision }

    override fun acquire(
        request: RemoteDispatchRequest,
        expectedRevision: Long,
        recordActivity: Boolean,
        cancel: () -> Unit,
    ): RemoteDispatchLease = synchronized(lock) {
        val categories = when (request.operation) {
            RemoteOperation.INFERENCE -> setOf(RemoteDataCategory.TEXT)
            RemoteOperation.PROVIDER_CHECK -> setOf(RemoteDataCategory.ACCOUNT_METADATA)
            else -> throw RemoteDispatchBlocked()
        }
        if (stopped || expectedRevision != intentRevision || request.providerId != providerId ||
            request.categories != categories || (!endpoint.isLoopback && !allowRemote) ||
            leases.size >= MAX_LEASES) throw RemoteDispatchBlocked()
        // Validation and lease registration are one atomic operation. recordActivity
        // does not enable a second history owner; this host has no dispatch ledger.
        Lease(intentRevision, cancel).also { leases.add(it) }
    }

    private inner class Lease(val admittedRevision: Long, val cancel: () -> Unit) : RemoteDispatchLease {
        var active = true // Only accessed under lock.

        override fun requireCurrent() = synchronized(lock) {
            if (!active || stopped || admittedRevision != intentRevision) throw RemoteDispatchBlocked()
        }

        override fun finish(outcome: RemoteDispatchOutcome, providerReportedCostUsd: String?) {
            synchronized(lock) {
                if (active) {
                    active = false
                    leases.remove(this)
                }
            }
        }
    }

    override fun close() {
        val callbacks = synchronized(lock) {
            if (stopped) return
            stopped = true
            intentRevision++
            leases.map { lease -> lease.active = false; lease.cancel }.also { leases.clear() }
        }
        // OkHttp cancellation may re-enter finish(). Never invoke callbacks under
        // the policy lock; failure of one cancellation must not strand other calls.
        callbacks.forEach { cancel -> try { cancel() } catch (_: Exception) { } }
    }

    companion object { const val MAX_LEASES = 8 }
}
