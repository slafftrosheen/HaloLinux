package com.halo.adapters.audio

import com.halo.libraries.common.core.Identifier
import com.halo.sdk.runtime.AudioContext
import com.halo.sdk.runtime.AudioDevice
import com.halo.sdk.runtime.AudioDeviceType
import com.halo.sdk.runtime.AudioSampleFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Linux audio device enumeration and diagnostics.
 * 
 * Uses ALSA/PipeWire interfaces to discover audio devices and
 * verify audio hardware availability. Reports unknown capabilities
 * as unknown rather than fabricating sample rates or channels.
 */
class LinuxAudioDiagnostics {
    private val log = LoggerFactory.getLogger(LinuxAudioDiagnostics::class.java)

    data class AudioDeviceInfo(
        val id: String,
        val name: String,
        val deviceType: AudioDeviceType,
        val sampleRates: List<Int>,
        val formats: List<AudioSampleFormat>,
        val channels: Int,
        val isDefault: Boolean,
        val isAvailable: Boolean,
    )

    data class AudioDiagnosticsResult(
        val inputDevices: List<AudioDeviceInfo>,
        val outputDevices: List<AudioDeviceInfo>,
        val defaultInput: String?,
        val defaultOutput: String?,
        val pipeWireAvailable: Boolean,
        val alsaAvailable: Boolean,
        val errors: List<String>,
    )

    /**
     * Enumerate all audio devices on the Linux system.
     * 
     * This implementation reads from /proc/asound for ALSA devices.
     * PipeWire device discovery is not implemented to avoid unbounded
     * external process calls. Unknown capabilities are reported as empty lists.
     */
    suspend fun enumerateDevices(): AudioDiagnosticsResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val inputDevices = mutableListOf<AudioDeviceInfo>()
        val outputDevices = mutableListOf<AudioDeviceInfo>()
        var defaultInput: String? = null
        var defaultOutput: String? = null

        val alsaAvailable = checkAlsaAvailable()
        val pipeWireAvailable = false // Disabled to avoid unbounded pw-cli calls

        if (alsaAvailable) {
            val alsaDevices = enumerateAlsaDevices()
            inputDevices.addAll(alsaDevices.filter { it.deviceType == AudioDeviceType.INPUT })
            outputDevices.addAll(alsaDevices.filter { it.deviceType == AudioDeviceType.OUTPUT })
            defaultInput = alsaDevices.firstOrNull { it.deviceType == AudioDeviceType.INPUT && it.isDefault }?.id
            defaultOutput = alsaDevices.firstOrNull { it.deviceType == AudioDeviceType.OUTPUT && it.isDefault }?.id
        } else {
            errors.add("ALSA not available. Check /proc/asound/cards and /proc/asound/devices.")
        }

        if (inputDevices.isEmpty() && outputDevices.isEmpty()) {
            errors.add("No audio devices found. Check ALSA configuration.")
        }

        AudioDiagnosticsResult(
            inputDevices = inputDevices,
            outputDevices = outputDevices,
            defaultInput = defaultInput,
            defaultOutput = defaultOutput,
            pipeWireAvailable = pipeWireAvailable,
            alsaAvailable = alsaAvailable,
            errors = errors,
        )
    }

    private fun checkAlsaAvailable(): Boolean {
        return try {
            java.io.File("/proc/asound/cards").exists() ||
            java.io.File("/proc/asound/devices").exists()
        } catch (e: Exception) {
            false
        }
    }

    private fun enumerateAlsaDevices(): List<AudioDeviceInfo> {
        val devices = mutableListOf<AudioDeviceInfo>()

        try {
            val cardsFile = java.io.File("/proc/asound/cards")
            if (cardsFile.exists()) {
                val lines = cardsFile.readText().lines()
                var cardIndex = -1
                var cardName = ""

                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue

                    // Parse card lines like " 0 [Headphones     ]: USB-Audio - Headphones"
                    if (trimmed.startsWith(" ") && trimmed.contains("[")) {
                        val parts = trimmed.split(" ")
                        cardIndex = parts.firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: -1
                        val bracketStart = trimmed.indexOf("[")
                        val bracketEnd = trimmed.indexOf("]")
                        if (bracketStart >= 0 && bracketEnd > bracketStart) {
                            cardName = trimmed.substring(bracketStart + 1, bracketEnd).trim()
                        }
                    } else if (trimmed.startsWith(" ") && cardIndex >= 0) {
                        // Subdevice lines - we don't have reliable sample rate/format info from /proc/asound
                        // Report unknown capabilities as empty lists rather than fabricating values
                        if (trimmed.contains("playback")) {
                            devices.add(AudioDeviceInfo(
                                id = "alsa-hw:$cardIndex,0",
                                name = "$cardName (Playback)",
                                deviceType = AudioDeviceType.OUTPUT,
                                sampleRates = emptyList(), // Unknown - would require ioctl or pw-cli
                                formats = emptyList(),     // Unknown
                                channels = 0,              // Unknown
                                isDefault = false,
                                isAvailable = true,
                            ))
                        }
                        if (trimmed.contains("capture")) {
                            devices.add(AudioDeviceInfo(
                                id = "alsa-hw:$cardIndex,0",
                                name = "$cardName (Capture)",
                                deviceType = AudioDeviceType.INPUT,
                                sampleRates = emptyList(), // Unknown
                                formats = emptyList(),     // Unknown
                                channels = 0,              // Unknown
                                isDefault = false,
                                isAvailable = true,
                            ))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to enumerate ALSA devices: ${e.message}")
        }

        return devices
    }
}

/**
 * Audio adapter factory for creating Linux audio components.
 */
class LinuxAudioAdapterFactory {
    suspend fun createDiagnostics(): LinuxAudioDiagnostics = LinuxAudioDiagnostics()
}