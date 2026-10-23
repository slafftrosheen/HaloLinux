package com.halo.adapters.audio

import java.nio.ByteOrder

/** Explicit host routing. Names are operator input, never inferred from model output. */
enum class LinuxAudioBackend { ALSA, PIPEWIRE }
enum class AudioFailure {
    INVALID_ROUTE, INVALID_AUDIO, INVALID_ARGUMENTS, INPUT_INVALID, BUSY, CLOSED,
    BACKEND_UNAVAILABLE, BACKEND_FAILED, IO_FAILED, CLEANUP_FAILED, UNSUPPORTED_PLATFORM
}
class LinuxAudioException(val code: AudioFailure) : IllegalArgumentException(code.name)

data class LinuxAudioRoute(val backend: LinuxAudioBackend, val device: String) {
    init {
        if (!Regex("[A-Za-z0-9][A-Za-z0-9_.:,=-]{0,127}").matches(device) ||
            (backend == LinuxAudioBackend.PIPEWIRE && device == "0")) {
            throw LinuxAudioException(AudioFailure.INVALID_ROUTE)
        }
    }

    internal fun command(rate: Int, channels: Int): List<String> = when (backend) {
        LinuxAudioBackend.ALSA -> listOf("/usr/bin/aplay", "--quiet", "--nonblock",
            "--fatal-errors", "--file-type=wav", "--device=$device")
        // Trixie's pw-cat 1.4.2 treats '-' as raw stdin; --raw is a newer option.
        LinuxAudioBackend.PIPEWIRE -> {
            if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) {
                throw LinuxAudioException(AudioFailure.UNSUPPORTED_PLATFORM)
            }
            listOf("/usr/bin/pw-cat", "--playback", "--target=$device", "--rate=$rate",
                "--channels=$channels", "--format=s16", "--channel-map=${if (channels == 1) "mono" else "stereo"}",
                "--properties={\"node.dont-fallback\":true,\"node.dont-reconnect\":true,\"node.dont-move\":true,\"node.linger\":false}", "-")
        }
    }
}

/** Fixed executable argv, no shell, no child diagnostics or inherited provider secrets. */
internal fun playerProcessBuilder(
    command: List<String>, environment: Map<String, String> = System.getenv(),
): ProcessBuilder = ProcessBuilder(command).apply {
    redirectOutput(ProcessBuilder.Redirect.DISCARD)
    redirectError(ProcessBuilder.Redirect.DISCARD)
    environment().clear()
    for (key in listOf("HOME", "USER", "LOGNAME", "XDG_RUNTIME_DIR", "XDG_CONFIG_HOME",
        "XDG_DATA_HOME", "DBUS_SESSION_BUS_ADDRESS")) {
        environment[key]?.let { environment()[key] = it }
    }
    environment()["PATH"] = "/usr/bin:/bin"
    environment()["LC_ALL"] = "C"
}
