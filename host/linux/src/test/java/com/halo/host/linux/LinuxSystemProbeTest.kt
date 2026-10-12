package com.halo.host.linux

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LinuxSystemProbeTest {
    @TempDir lateinit var tempDir: Path

    @Test fun parsesLinuxMeminfoAndThermalTemperature() {
        val proc = tempDir.resolve("proc")
        val sys = tempDir.resolve("sys")
        Files.createDirectories(proc)
        Files.createDirectories(sys.resolve("class/thermal/thermal_zone0"))
        Files.writeString(proc.resolve("meminfo"),
            "MemTotal:        8100000 kB\nMemAvailable:   4500000 kB\n")
        Files.writeString(sys.resolve("class/thermal/thermal_zone0/temp"), "43789\n")
        val result = LinuxSystemProbe.snapshot(proc, sys)
        assertEquals(8_100_000L * 1024L, result.memoryTotalBytes)
        assertEquals(4_500_000L * 1024L, result.memoryAvailableBytes)
        assertEquals(43.789, result.socTemperatureC!!, 0.001)
    }

    @Test fun unknownSensorsRemainUnknown() {
        val result = LinuxSystemProbe.snapshot(tempDir.resolve("absent"), tempDir.resolve("absent"))
        assertNull(result.memoryTotalBytes)
        assertNull(result.memoryAvailableBytes)
        assertNull(result.socTemperatureC)
    }
}
