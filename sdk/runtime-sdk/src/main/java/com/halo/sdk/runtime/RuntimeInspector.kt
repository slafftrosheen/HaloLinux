package com.halo.sdk.runtime

interface RuntimeInspector {
    suspend fun snapshot(): RuntimeSnapshot
    suspend fun events(): List<EventEnvelope<*>>
    suspend fun metrics(): RuntimeMetrics
}
