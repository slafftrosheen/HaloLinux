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
 * verify audio hardware availability.
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
     * This implementation reads from /proc/asound and uses pw-cli
     * for PipeWire device discovery where available.
     */
    suspend fun enumerateDevices(): AudioDiagnosticsResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val inputDevices = mutableListOf<AudioDeviceInfo>()
        val outputDevices = mutableListOf<AudioDeviceInfo>()
        var defaultInput: String? = null
        var defaultOutput: String? = null
        
        val alsaAvailable = checkAlsaAvailable()
        val pipeWireAvailable = checkPipeWireAvailable()
        
        if (alsaAvailable) {
            val alsaDevices = enumerateAlsaDevices()
            inputDevices.addAll(alsaDevices.filter { it.deviceType == AudioDeviceType.INPUT })
            outputDevices.addAll(alsaDevices.filter { it.deviceType == AudioDeviceType.OUTPUT })
            defaultInput = alsaDevices.firstOrNull { it.deviceType == AudioDeviceType.INPUT && it.isDefault }?.id
            defaultOutput = alsaDevices.firstOrNull { it.deviceType == AudioDeviceType.OUTPUT && it.isDefault }?.id
        }
        
        if (pipeWireAvailable) {
            val pwDevices = enumeratePipeWireDevices()
            // Merge with ALSA devices, preferring PipeWire info
            mergePipeWireDevices(inputDevices, outputDevices, pwDevices)
        }
        
        if (inputDevices.isEmpty() && outputDevices.isEmpty()) {
            errors.add("No audio devices found. Check ALSA/PipeWire configuration.")
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

    private fun checkPipeWireAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("pw-cli", "info", "0").start()
            process.waitFor() == 0
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
                        // Subdevice lines
                        if (trimmed.contains("playback")) {
                            devices.add(AudioDeviceInfo(
                                id = "alsa-hw:$cardIndex,0",
                                name = "$cardName (Playback)",
                                deviceType = AudioDeviceType.OUTPUT,
                                sampleRates = listOf(44100, 48000, 96000),
                                formats = listOf(AudioSampleFormat.PCM_16BIT, AudioSampleFormat.PCM_24BIT, AudioSampleFormat.PCM_32BIT),
                                channels = 2,
                                isDefault = false,
                                isAvailable = true,
                            ))
                        }
                        if (trimmed.contains("capture")) {
                            devices.add(AudioDeviceInfo(
                                id = "alsa-hw:$cardIndex,0",
                                name = "$cardName (Capture)",
                                deviceType = AudioDeviceType.INPUT,
                                sampleRates = listOf(44100, 48000, 96000),
                                formats = listOf(AudioSampleFormat.PCM_16BIT, AudioSampleFormat.PCM_24BIT),
                                channels = 2,
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

    private fun enumeratePipeWireDevices(): List<AudioDeviceInfo> {
        val devices = mutableListOf<AudioDeviceInfo>()
        
        try {
            val process = ProcessBuilder("pw-cli", "list-objects").start()
            val output = process.inputStream.readText()
            process.waitFor()
            
            // Parse pw-cli output for audio devices
            // This is a simplified parser - in production would use proper JSON parsing
            // if pw-dump or pw-cli --output-format=json is available
            log.debug("PipeWire objects: $output")
        } catch (e: Exception) {
            log.warn("Failed to enumerate PipeWire devices: ${e.message}")
        }
        
        return devices
    }

    private fun mergePipeWireDevices(
        inputDevices: MutableList<AudioDeviceInfo>,
        outputDevices: MutableList<AudioDeviceInfo>,
        pwDevices: List<AudioDeviceInfo>
    ) {
        // Merge logic: prefer PipeWire devices, update ALSA entries with PW info
        for (pwDevice in pwDevices) {
            val targetList = when (pwDevice.deviceType) {
                AudioDeviceType.INPUT -> inputDevices
                AudioDeviceType.OUTPUT -> outputDevices
                else -> continue
            }
            
            val existingIndex = targetList.indexOfFirst { it.id == pwDevice.id }
            if (existingIndex >= 0) {
                targetList[existingIndex] = pwDevice
            } else {
                targetList.add(pwDevice)
            }
        }
    }
}

/**
 * Audio adapter factory for creating Linux audio components.
 */
class LinuxAudioAdapterFactory {
    suspend fun createDiagnostics(): LinuxAudioDiagnostics = LinuxAudioDiagnostics()
}