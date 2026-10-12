package com.halo.sdk.provider

import com.halo.sdk.runtime.HaloEvent

import java.util.UUID

data class AIRequestEvent(
    override val id: String = UUID.randomUUID().toString(),
    val prompt: String,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent

data class AIResponseEvent(
    override val id: String = UUID.randomUUID().toString(),
    val response: String,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent
