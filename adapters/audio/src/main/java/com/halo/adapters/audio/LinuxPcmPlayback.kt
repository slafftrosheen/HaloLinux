package com.halo.adapters.audio

import com.halo.sdk.runtime.multimodal.Pcm16Wav
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Backend completion is not an audible-position or word-timing acknowledgement. */
enum class PlaybackState { COMPLETED, CANCELLED, TIMED_OUT, FAILED }
data class PlaybackResult(
    val state: PlaybackState,
    val frames: Long,
    val sampleRateHz: Int,
    val channels: Int,
    val exitCode: Int? = null,
    val failure: AudioFailure? = null,
)

/**
 * Bounded PCM16 output, one request at a time. play blocks until child cleanup;
 * stop/close may be called from another thread (for example a future barge-in owner).
 * No queue, fallback route, microphone, model or service ownership lives here.
 */
class LinuxPcmPlayback internal constructor(
    private val route: LinuxAudioRoute,
    private val launch: (List<String>) -> Process,
    private val timeoutOverrideMs: Long?,
) : AutoCloseable {
    constructor(route: LinuxAudioRoute) : this(route, { playerProcessBuilder(it).start() }, null)

    private class Session(val owner: Thread, val deadline: Long) {
        var process: Process? = null
        var stopped: PlaybackState? = null
    }
    private val lock = Object()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "halo-pcm-watchdog").apply { isDaemon = true }
    }
    private var closed = false
    private var active: Session? = null

    init {
        require(timeoutOverrideMs == null || timeoutOverrideMs in 25..125_000)
    }

    fun play(pcm16: ByteArray, sampleRateHz: Int, channels: Int): PlaybackResult {
        if (!System.getProperty("os.name").startsWith("Linux", ignoreCase = true)) {
            throw LinuxAudioException(AudioFailure.UNSUPPORTED_PLATFORM)
        }
        // Canonical validation also makes a private copy before process dispatch.
        val wav = Pcm16Wav.wrap(pcm16, sampleRateHz, channels)
            ?: throw LinuxAudioException(AudioFailure.INVALID_AUDIO)
        val frames = pcm16.size.toLong() / (channels * 2)
        if (frames > sampleRateHz.toLong() * 120) throw LinuxAudioException(AudioFailure.INVALID_AUDIO)
        val command = route.command(sampleRateHz, channels)
        val durationMs = (frames * 1000 + sampleRateHz - 1) / sampleRateHz
        val deadlineMs = timeoutOverrideMs ?: (durationMs + 5000)
        val session = Session(Thread.currentThread(), System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(deadlineMs))
        val timer: ScheduledFuture<*>
        synchronized(lock) {
            if (closed) throw LinuxAudioException(AudioFailure.CLOSED)
            if (active != null) throw LinuxAudioException(AudioFailure.BUSY)
            active = session
            // Installed under the same lock as close/admission; no shutdown race.
            timer = watchdog.scheduleAtFixedRate({
                if (session.owner.isInterrupted) requestStop(session, PlaybackState.CANCELLED)
                else if (System.nanoTime() - session.deadline >= 0) requestStop(session, PlaybackState.TIMED_OUT)
            }, 0, 25, TimeUnit.MILLISECONDS)
        }
        var process: Process? = null
        var exitCode: Int? = null
        var failure: AudioFailure? = null
        var interrupted = false
        var cleaned = true
        try {
            if (Thread.currentThread().isInterrupted) requestStop(session, PlaybackState.CANCELLED)
            if (stopReason(session) == null) {
                process = try { launch(command) } catch (_: IOException) {
                    failure = AudioFailure.BACKEND_UNAVAILABLE; null
                }
                process?.let { child ->
                    val stopNow = synchronized(lock) { session.process = child; session.stopped != null }
                    if (stopNow) kill(child)
                    else {
                        child.outputStream.use { input ->
                            var offset = if (route.backend == LinuxAudioBackend.ALSA) 0 else Pcm16Wav.HEADER_BYTES
                            while (offset < wav.size && stopReason(session) == null) {
                                val count = minOf(64 * 1024, wav.size - offset)
                                input.write(wav, offset, count)
                                offset += count
                            }
                        }
                    }
                    val remaining = maxOf(1, TimeUnit.NANOSECONDS.toMillis(session.deadline - System.nanoTime()))
                    if (!child.waitFor(remaining, TimeUnit.MILLISECONDS)) requestStop(session, PlaybackState.TIMED_OUT)
                    if (!child.isAlive) {
                        exitCode = child.exitValue()
                        if (exitCode != 0 && stopReason(session) == null) failure = AudioFailure.BACKEND_FAILED
                    }
                }
            }
        } catch (_: InterruptedException) {
            interrupted = true
            requestStop(session, PlaybackState.CANCELLED)
        } catch (_: IOException) {
            if (stopReason(session) == null) failure = AudioFailure.IO_FAILED
        } catch (_: Exception) {
            if (stopReason(session) == null) failure = AudioFailure.BACKEND_FAILED
        } finally {
            timer.cancel(false)
            // Process.destroy() can close a pipe while another thread holds its write
            // lock. Signal the process handle first, then close streams after exit.
            process?.let { child ->
                if (child.isAlive) kill(child)
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                while (child.isAlive && System.nanoTime() - until < 0) {
                    try { child.waitFor(25, TimeUnit.MILLISECONDS) }
                    catch (_: InterruptedException) { interrupted = true; requestStop(session, PlaybackState.CANCELLED) }
                }
                cleaned = !child.isAlive
                if (cleaned) {
                    exitCode = child.exitValue()
                    for (stream in listOf(child.outputStream, child.inputStream, child.errorStream)) {
                        try { stream.close() } catch (_: IOException) { /* Already closed after child exit. */ }
                    }
                }
            }
            if (Thread.currentThread().isInterrupted) interrupted = true
        }
        val state = synchronized(lock) {
            if (interrupted && session.stopped == null) session.stopped = PlaybackState.CANCELLED
            if (!cleaned) { closed = true; failure = AudioFailure.CLEANUP_FAILED; watchdog.shutdown() }
            val result = if (!cleaned) PlaybackState.FAILED else session.stopped
                ?: if (failure == null && exitCode == 0) PlaybackState.COMPLETED else PlaybackState.FAILED
            active = null
            lock.notifyAll()
            result
        }
        if (interrupted) Thread.currentThread().interrupt()
        return PlaybackResult(state, frames, sampleRateHz, channels, exitCode, failure)
    }

    /** True only when this call accepted cancellation of a still-owned session. */
    fun stop(): Boolean {
        val session = synchronized(lock) { active } ?: return false
        return requestStop(session, PlaybackState.CANCELLED)
    }

    private fun stopReason(session: Session): PlaybackState? = synchronized(lock) { session.stopped }

    private fun requestStop(session: Session, reason: PlaybackState): Boolean {
        val child = synchronized(lock) {
            if (active !== session || session.stopped != null) return false
            session.stopped = reason
            session.process
        }
        child?.let(::kill)
        return true
    }

    private fun kill(child: Process) {
        try { child.toHandle().destroyForcibly() } catch (_: Exception) { /* Cleanup reports failure. */ }
    }

    override fun close() {
        val session = synchronized(lock) {
            if (closed) return
            closed = true
            active
        }
        session?.let { requestStop(it, PlaybackState.CANCELLED) }
        watchdog.shutdown()
        // play owns reaping and streams. Waiting is bounded and never waits on self.
        if (session != null && session.owner !== Thread.currentThread()) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            synchronized(lock) {
                while (active === session && System.nanoTime() - until < 0) {
                    try { lock.wait(25) }
                    catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                }
            }
        }
    }
}
