package com.halo.sdk.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class NativeMemorySample(val availableBytes: Long, val totalBytes: Long, val lowMemory: Boolean, val platformThresholdBytes: Long)

/** Transient callbacks trigger bounded probes. Serialization prevents an old probe publishing over a new callback. */
class NativeMemoryPressureRecovery(
    private val scope: CoroutineScope,
    private val sample: suspend () -> NativeMemorySample,
    private val publish: suspend (NativeMemoryPressure, NativeMemoryPressure, NativeMemorySample, String) -> Unit,
    private val probeDelaysMs: List<Long> = listOf(5_000, 15_000, 30_000),
) {
    private val mutex = Mutex()
    private var pressure = NativeMemoryPressure.NORMAL
    private var generation = 0L
    private var recovery: Job? = null

    suspend fun update(transient: NativeMemoryPressure? = null, source: String = "LIFECYCLE") = mutex.withLock {
        generation++
        recovery?.cancel()
        val info = sample()
        val next = transient?.let { if (info.lowMemory) NativeMemoryPressure.CRITICAL else it } ?: resolve(info)
        transition(next, info, source)
        if (next != NativeMemoryPressure.NORMAL) {
            val token = generation
            recovery = scope.launch {
                for (wait in probeDelaysMs) {
                    delay(wait)
                    val recovered = mutex.withLock {
                        if (token != generation) return@launch
                        val current = sample()
                        transition(resolve(current), current, "RECOVERY_PROBE")
                        pressure == NativeMemoryPressure.NORMAL
                    }
                    if (recovered) break
                }
            }
        }
    }

    private fun resolve(info: NativeMemorySample): NativeMemoryPressure {
        if (info.lowMemory) return NativeMemoryPressure.CRITICAL
        val lowThreshold = maxOf(info.platformThresholdBytes, info.totalBytes / 10)
        val recoveryThreshold = lowThreshold + lowThreshold / 2
        return if (if (pressure == NativeMemoryPressure.NORMAL) info.availableBytes < lowThreshold else info.availableBytes <= recoveryThreshold)
            NativeMemoryPressure.LOW else NativeMemoryPressure.NORMAL
    }

    private suspend fun transition(next: NativeMemoryPressure, info: NativeMemorySample, source: String) {
        val previous = pressure
        pressure = next
        publish(previous, next, info, source)
    }
}
