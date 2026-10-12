package com.halo.sdk.runtime
import java.util.UUID

data class EventEnvelope<T : HaloEvent>(
    val id: EventId,
    val event: T,
    val createdAt: Long,
    val sourceEngine: EngineId,
    val intentId: UUID?,
    val parentId: EventId?,
    val traceId: String?,
    val spanId: String?,
    val parentSpanId: String?
)
