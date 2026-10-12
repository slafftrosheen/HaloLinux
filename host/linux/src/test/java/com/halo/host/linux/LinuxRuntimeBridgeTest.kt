package com.halo.host.linux

import com.halo.sdk.runtime.EngineHealth
import com.halo.sdk.runtime.EngineState
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LinuxRuntimeBridgeTest {
    @Test fun createsAndStopsPortableRuntime() = runBlocking {
        val bridge = LinuxRuntimeBridge()
        assertEquals(EngineState.Created, bridge.engine.state.value)
        bridge.start()
        assertEquals(EngineState.Running, bridge.engine.state.value)
        assertEquals(EngineHealth.Healthy, bridge.engine.health.value)
        assertNotNull(bridge.registeredResources().snapshot())
        bridge.stop()
        assertEquals(EngineState.Disposed, bridge.engine.state.value)
        assertEquals(EngineHealth.Offline, bridge.engine.health.value)
    }
}
