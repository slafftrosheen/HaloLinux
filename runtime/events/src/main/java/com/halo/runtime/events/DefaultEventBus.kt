package com.halo.runtime.events

import com.halo.sdk.runtime.HaloEvent
import com.halo.sdk.runtime.EventBus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

class DefaultEventBus : EventBus {
    private val stream = MutableSharedFlow<HaloEvent>(replay = 0, extraBufferCapacity = 128)

    override suspend fun publish(event: HaloEvent) {
        // This is the hot path for streamed tokens as well as state events.
        // Per-event stdout logging adds blocking IO and can amplify latency;
        // use the existing bounded runtime trace/diagnostic observers instead.
        stream.emit(event)
    }

    override fun events(): Flow<HaloEvent> = stream.asSharedFlow()

    @Suppress("UNCHECKED_CAST")
    override fun <T : HaloEvent> observe(type: Class<T>): Flow<T> =
        stream.asSharedFlow()
            .filter { type.isInstance(it) }
            .map { it as T }
}
