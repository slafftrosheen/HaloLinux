package com.halo.adapters.audio

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Conservative hardware diagnostics: available audio subsystems do not imply
 * capture/playback readiness. Format, rate and direction are not guessed.
 */
class LinuxAudioDiagnostics {
    data class AudioDiagnosticsResult(
        val alsaAvailable: Boolean,
        val pipeWireAvailable: Boolean,
        val cards: List<String>,
        val errors: List<String>,
    )

    suspend fun enumerateDevices(): AudioDiagnosticsResult = withContext(Dispatchers.IO) {
        val cardsPath = Path.of("/proc/asound/cards")
        val cards = runCatching {
            if (Files.isRegularFile(cardsPath)) Files.readAllLines(cardsPath)
                .map(String::trim).filter(String::isNotBlank) else emptyList()
        }.getOrDefault(emptyList())
        val pipeWire = runCatching {
            val proc = ProcessBuilder("pw-cli", "info", "0")
                .redirectErrorStream(true).start()
            if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                false
            } else proc.exitValue() == 0
        }.getOrDefault(false)
        AudioDiagnosticsResult(
            alsaAvailable = Files.isDirectory(Path.of("/proc/asound")),
            pipeWireAvailable = pipeWire,
            cards = cards,
            errors = if (cards.isEmpty() && !pipeWire) listOf("No usable audio endpoint verified") else emptyList(),
        )
    }
}

class LinuxAudioAdapterFactory {
    suspend fun createDiagnostics(): LinuxAudioDiagnostics = LinuxAudioDiagnostics()
}
