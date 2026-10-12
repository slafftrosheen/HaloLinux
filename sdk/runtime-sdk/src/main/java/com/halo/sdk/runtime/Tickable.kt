package com.halo.sdk.runtime
import java.time.Duration

interface Tickable {
    suspend fun tick(deltaTime: Duration)
}
