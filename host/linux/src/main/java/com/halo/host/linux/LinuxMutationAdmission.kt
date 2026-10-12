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
        var locked = false
        try {
            // withTimeoutOrNull can return null just after lock() completes.
            // Track ownership outside its body so that cancellation cannot
            // leak the mutex in this narrow acquire/timeout race.
            val admitted = withTimeoutOrNull(waitMs) {
                gate.lock()
                locked = true
                true
            } ?: return null
            check(admitted)
            return block()
        } finally {
            if (locked) gate.unlock()
        }
    }
}
