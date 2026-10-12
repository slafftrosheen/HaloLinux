package com.halo.sdk.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Minimal stage metric sink usable by production code and deterministic tests. */
interface RuntimeMetricsSink {
    fun increment(counter: String, amount: Long = 1L)
    fun recordLatency(stage: RuntimeStage, latencyMillis: Long)
    fun counters(): Map<String, Long>
    fun latencies(): Map<RuntimeStage, List<Long>>
}

object NoopRuntimeMetrics : RuntimeMetricsSink {
    override fun increment(counter: String, amount: Long) = Unit
    override fun recordLatency(stage: RuntimeStage, latencyMillis: Long) = Unit
    override fun counters(): Map<String, Long> = emptyMap()
    override fun latencies(): Map<RuntimeStage, List<Long>> = emptyMap()
}

class InMemoryRuntimeMetrics : RuntimeMetricsSink {
    private val counters = ConcurrentHashMap<String, AtomicLong>()
    private val latencies = ConcurrentHashMap<RuntimeStage, MutableList<Long>>()

    override fun increment(counter: String, amount: Long) {
        require(counter.isNotBlank()) { "Metric counter must not be blank" }
        counters.computeIfAbsent(counter) { AtomicLong() }.addAndGet(amount)
    }

    override fun recordLatency(stage: RuntimeStage, latencyMillis: Long) {
        require(latencyMillis >= 0L) { "Latency must not be negative" }
        synchronized(latencies) {
            latencies.getOrPut(stage) { mutableListOf() }.add(latencyMillis)
        }
    }

    override fun counters(): Map<String, Long> = counters.mapValues { it.value.get() }

    override fun latencies(): Map<RuntimeStage, List<Long>> = synchronized(latencies) {
        latencies.mapValues { it.value.toList() }
    }
}