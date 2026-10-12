package com.halo.sdk.runtime

import java.util.UUID

/**
 * Base interface for all Halo memories.
 */
interface Memory {
    val id: UUID
}

data class SimpleMemory(
    override val id: UUID = UUID.randomUUID(),
    val content: String,
    val kind: String = "fact",
    val scope: String = "global"
) : Memory
