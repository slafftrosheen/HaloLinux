package com.halo.host.linux

import java.nio.file.Files
import java.nio.file.Path

data class LinuxSystemSnapshot(
    val architecture: String,
    val memoryTotalBytes: Long?,
    val memoryAvailableBytes: Long?,
    val socTemperatureC: Double?,
)

/**
 * Read-only Linux metrics. These values are observations, NOT proof of
 * performance, voltage stability, throttling, power margin, or inference readiness.
 */
object LinuxSystemProbe {
    fun snapshot(
        proc: Path = Path.of("/proc"),
        sys: Path = Path.of("/sys"),
    ): LinuxSystemSnapshot {
        val fields = runCatching {
            Files.readAllLines(proc.resolve("meminfo")).mapNotNull { line ->
                val match = Regex("^([A-Za-z_]+):\\s+(\\d+)\\s+kB").find(line)
                if (match == null) null else match.groupValues[1] to
                    (match.groupValues[2].toLongOrNull()?.times(1024L) ?: return@mapNotNull null)
            }.toMap()
        }.getOrDefault(emptyMap())
        val temp = runCatching {
            Files.readString(sys.resolve("class/thermal/thermal_zone0/temp"))
                .trim().toLongOrNull()?.div(1000.0)?.takeIf { it in -30.0..150.0 }
        }.getOrNull()
        return LinuxSystemSnapshot(
            architecture = System.getProperty("os.arch", "unknown"),
            memoryTotalBytes = fields["MemTotal"],
            memoryAvailableBytes = fields["MemAvailable"],
            socTemperatureC = temp,
        )
    }
}
