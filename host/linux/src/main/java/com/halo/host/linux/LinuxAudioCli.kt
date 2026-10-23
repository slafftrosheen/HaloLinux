package com.halo.host.linux

import com.halo.adapters.audio.AudioFailure
import com.halo.adapters.audio.LinuxAudioBackend
import com.halo.adapters.audio.LinuxAudioException
import com.halo.adapters.audio.LinuxAudioRoute
import com.halo.adapters.audio.LinuxPcmPlayback
import com.halo.adapters.audio.PlaybackState
import com.halo.sdk.runtime.multimodal.Pcm16Wav
import java.io.PrintStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes

/** Explicit foreground playback only; never opens config, the daemon, or a database. */
object LinuxAudioCli {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val USAGE = "Usage: halo-node audio play --backend alsa|pipewire --device NAME --rate HZ --channels 1|2 --pcm /absolute/file.pcm"

    fun run(args: List<String>): Int = run(args, System.out, System.err, ::LinuxPcmPlayback)

    internal fun run(
        args: List<String>, out: PrintStream, err: PrintStream,
        factory: (LinuxAudioRoute) -> LinuxPcmPlayback,
    ): Int {
        var player: LinuxPcmPlayback? = null
        var hook: Thread? = null
        try {
            if (args == listOf("--help") || args == listOf("help")) {
                out.println(USAGE)
                out.println("Input is raw interleaved signed PCM16 little-endian, not WAV/MP3. Playback is audible; choose a safe device and volume first.")
                return 0
            }
            if (args.firstOrNull() != "play" || args.size != 11) throw LinuxAudioException(AudioFailure.INVALID_ARGUMENTS)
            val options = linkedMapOf<String, String>()
            val allowed = setOf("--backend", "--device", "--rate", "--channels", "--pcm")
            // Five explicit key/value options: no defaults that could select the wrong device.
            for (pair in args.drop(1).chunked(2)) {
                if (pair.size != 2 || pair[0] !in allowed || options.put(pair[0], pair[1]) != null) {
                    throw LinuxAudioException(AudioFailure.INVALID_ARGUMENTS)
                }
            }
            if (options.keys != allowed) throw LinuxAudioException(AudioFailure.INVALID_ARGUMENTS)
            val backend = when (options["--backend"]) {
                "alsa" -> LinuxAudioBackend.ALSA
                "pipewire" -> LinuxAudioBackend.PIPEWIRE
                else -> throw LinuxAudioException(AudioFailure.INVALID_ARGUMENTS)
            }
            fun number(name: String): Int {
                val value = checkNotNull(options[name])
                if (!Regex("[0-9]{1,6}").matches(value)) throw LinuxAudioException(AudioFailure.INVALID_ARGUMENTS)
                return value.toIntOrNull() ?: throw LinuxAudioException(AudioFailure.INVALID_ARGUMENTS)
            }
            val rate = number("--rate")
            val channels = number("--channels")
            if (rate !in 8000..96000 || channels !in 1..2) throw LinuxAudioException(AudioFailure.INVALID_ARGUMENTS)
            val route = LinuxAudioRoute(backend, checkNotNull(options["--device"]))
            val data = readPcm(Path.of(checkNotNull(options["--pcm"])))
            if (Pcm16Wav.looksLikeWav(data)) throw LinuxAudioException(AudioFailure.INVALID_AUDIO)
            player = factory(route)
            val current = player
            hook = Thread({ current.close() }, "halo-audio-shutdown")
            Runtime.getRuntime().addShutdownHook(hook)
            val result = current.play(data, rate, channels)
            out.println("{\"state\":\"${result.state}\",\"input_frames\":${result.frames},\"sample_rate_hz\":$rate,\"channels\":$channels}")
            if (result.failure != null) err.println("Audio error: ${result.failure.name}")
            return when (result.state) {
                PlaybackState.COMPLETED -> 0
                PlaybackState.CANCELLED -> 130
                PlaybackState.TIMED_OUT -> 124
                PlaybackState.FAILED -> 1
            }
        } catch (e: LinuxAudioException) {
            err.println("Audio error: ${e.code.name}")
            if (e.code == AudioFailure.INVALID_ARGUMENTS) err.println(USAGE)
            return 2
        } catch (_: Exception) {
            err.println("Audio error: INPUT_INVALID")
            return 2
        } finally {
            player?.close()
            hook?.let { try { Runtime.getRuntime().removeShutdownHook(it) } catch (_: IllegalStateException) { /* JVM is stopping. */ } }
        }
    }

    private fun readPcm(path: Path): ByteArray {
        if (!path.isAbsolute || ".." in path.map { it.toString() }) throw LinuxAudioException(AudioFailure.INPUT_INVALID)
        var part = path.root
        for (component in path) {
            part = part.resolve(component)
            if (Files.isSymbolicLink(part)) throw LinuxAudioException(AudioFailure.INPUT_INVALID)
        }
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!before.isRegularFile || before.size() !in 1..MAX_BYTES.toLong()) throw LinuxAudioException(AudioFailure.INPUT_INVALID)
        val bytes = Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
            if (channel.size() != before.size()) throw LinuxAudioException(AudioFailure.INPUT_INVALID)
            Channels.newInputStream(channel).readNBytes(MAX_BYTES + 1)
        }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!after.isRegularFile || bytes.size.toLong() != before.size() || before.size() != after.size() ||
            before.lastModifiedTime() != after.lastModifiedTime() || before.fileKey() != after.fileKey()) {
            throw LinuxAudioException(AudioFailure.INPUT_INVALID)
        }
        return bytes
    }
}
