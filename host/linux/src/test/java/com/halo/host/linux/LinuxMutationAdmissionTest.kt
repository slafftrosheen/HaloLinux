package com.halo.host.linux

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LinuxMutationAdmissionTest {
    @Test fun waitingWriteFailsBusyWithoutEverExecuting() = runBlocking<Unit> {
        val gate = LinuxMutationAdmission(waitMs = 40)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async(Dispatchers.Default) {
            gate.execute {
                entered.complete(Unit)
                release.await()
                "committed"
            }
        }
        try {
            withTimeout(1000) { entered.await() }
            var enteredSecond = false
            val second = gate.execute {
                enteredSecond = true
                "must-not-run"
            }
            assertNull(second)
            assertFalse(enteredSecond)
        } finally {
            release.complete(Unit)
            assertEquals("committed", withTimeout(1000) { first.await() })
        }
        // A rejected queued request must never strand the gate.
        assertEquals("next", withTimeout(1000) { gate.execute { "next" } })
    }

    @Test fun admittedWriteIsNotCancelledByAdmissionTimeout() = runBlocking<Unit> {
        val gate = LinuxMutationAdmission(waitMs = 20)
        val result = gate.execute {
            delay(60)
            "durable"
        }
        assertEquals("durable", result)
    }
}
