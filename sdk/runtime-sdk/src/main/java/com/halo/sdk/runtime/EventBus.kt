package com.halo.sdk.runtime

import kotlinx.coroutines.flow.Flow

interface HaloEvent {
    val id: String
    val timestamp: Long
}

interface EventBus {
    suspend fun publish(event: HaloEvent)
    fun events(): Flow<HaloEvent>
    fun <T : HaloEvent> observe(type: Class<T>): Flow<T>
}

data class ObservationUpdated(
    override val id: String = java.util.UUID.randomUUID().toString(),
    val observation: Observation,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class SafetyStateChanged(
    override val id: String = java.util.UUID.randomUUID().toString(),
    val state: Int,
    val fault: Int,
    val estopped: Boolean,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class ProviderFailed(
    override val id: String = java.util.UUID.randomUUID().toString(),
    val providerId: String,
    val reason: String,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent
