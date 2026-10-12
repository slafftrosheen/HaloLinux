package com.halo.sdk.runtime
import java.time.Instant

interface RuntimeClock {
    fun now(): Instant
    fun millis(): Long
}

class SystemRuntimeClock : RuntimeClock {
    override fun now() = Instant.now()
    override fun millis() = System.currentTimeMillis()
}
