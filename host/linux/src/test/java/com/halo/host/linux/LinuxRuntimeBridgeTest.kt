package com.halo.host.linux

import com.halo.sdk.runtime.EngineHealth
import com.halo.sdk.runtime.EngineState
import kotlinx.coroutines.runBlocking
import com.halo.adapters.storage.LinuxStorage
import com.halo.adapters.storage.LinuxStorageConfig
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LinuxRuntimeBridgeTest {
    @TempDir lateinit var tempDir: Path
    @Test fun createsAndStopsPortableRuntime() = runBlocking {
        val storage = LinuxStorage()
        storage.initialize(LinuxStorageConfig(
            dataDir=tempDir.resolve("data").toString(),
            configDir=tempDir.resolve("config").toString(),
            cacheDir=tempDir.resolve("cache").toString()
        ))
        val bridge = LinuxRuntimeBridge(storage)
        assertEquals(EngineState.Created, bridge.engine.state.value)
        bridge.start()
        assertEquals(EngineState.Running, bridge.engine.state.value)
        assertEquals(EngineHealth.Healthy, bridge.engine.health.value)
        assertNotNull(bridge.registeredResources().snapshot())
        bridge.stop()
        assertEquals(EngineState.Disposed, bridge.engine.state.value)
        assertEquals(EngineHealth.Offline, bridge.engine.health.value)
        storage.close()
    }
}
