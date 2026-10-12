package com.halo.host.linux

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bounded queue admission, NOT a deadline on an accepted operation.
 *
 * Once admitted, a durable SQLite write or model generation may run to its
 * own explicit timeout. Requests that cannot acquire the write gate promptly
 * are rejected rather than silently executed after the caller has timed out.
 */
internal class LinuxMutationAdmission(private val waitMs: Long = 5_000) {
    private val gate = Mutex()

    init { require(waitMs > 0) }

    suspend fun <T : Any> execute(block: suspend () -> T): T? {
        val acquired = withTimeoutOrNull(waitMs) {
            gate.lock()
            true
        } ?: return null
        try {
            return block()
        } finally {
            if (acquired) gate.unlock()
        }
    }
}
