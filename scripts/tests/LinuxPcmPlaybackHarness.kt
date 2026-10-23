import com.halo.adapters.audio.*
import com.halo.host.linux.LinuxAudioCli
import com.halo.sdk.runtime.multimodal.Pcm16Wav
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
private val classpath = System.getProperty("java.class.path")
private val route = LinuxAudioRoute(LinuxAudioBackend.ALSA, "null")
private val pcm = ByteArray(3200) { (it % 251).toByte() }
private var passed = 0
private fun case(name: String, test: () -> Unit) {
    test(); passed++; println("PASS $name")
}
private fun refused(code: AudioFailure, work: () -> Unit) {
    try { work(); error("Expected $code") } catch (e: LinuxAudioException) {
        check(e.code == code) { "Expected $code, got ${e.code}" }
        check(e.message == code.name)
    }
}
private fun waitForFile(path: Path) {
    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (!Files.exists(path)) {
        check(System.nanoTime() < until) { "Fixture did not become ready" }; Thread.sleep(5)
    }
}
private data class Fixture(val directory: Path, val mode: String) : AutoCloseable {
    val output: Path = directory.resolve("bytes")
    val ready: Path = directory.resolve("ready")
    val process = AtomicReference<Process>()
    val arguments = AtomicReference<List<String>>()
    fun start(args: List<String>): Process {
        arguments.set(args.toList())
        val command = listOf(javaBin, "-Xmx64m", "-cp", classpath,
            "LinuxPcmPlaybackHarnessKt", "fixture", mode, output.toString(), ready.toString())
        return playerProcessBuilder(command).start().also(process::set)
    }
    override fun close() {
        process.get()?.let { if (it.isAlive) it.toHandle().destroyForcibly(); it.waitFor(5, TimeUnit.SECONDS) }
        Files.walk(directory).use { entries -> entries.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
private fun fixture(mode: String = "capture") = Fixture(Files.createTempDirectory("halo-pcm-fixture-"), mode)
private class Running(val player: LinuxPcmPlayback, data: ByteArray = pcm, rate: Int = 16000, channels: Int = 1) {
    val result = AtomicReference<PlaybackResult>()
    val error = AtomicReference<Throwable>()
    val worker = thread {
        try { result.set(player.play(data, rate, channels)) } catch (t: Throwable) { error.set(t) }
    }
    fun finish(): PlaybackResult {
        worker.join(6000); check(!worker.isAlive) { "Playback failed to terminate" }
        error.get()?.let { throw it }
        return checkNotNull(result.get())
    }
}
private fun cli(arguments: List<String>, factory: (LinuxAudioRoute) -> LinuxPcmPlayback): Triple<Int, String, String> {
    val output = ByteArrayOutputStream(); val errors = ByteArrayOutputStream()
    val code = PrintStream(output).use { out -> PrintStream(errors).use { err ->
        LinuxAudioCli.run(arguments, out, err, factory)
    } }
    return Triple(code, output.toString("UTF-8"), errors.toString("UTF-8"))
}
private fun arguments(file: Path) = listOf("play", "--backend", "alsa", "--device", "null",
    "--rate", "16000", "--channels", "1", "--pcm", file.toString())

fun main(args: Array<String>) {
    if (args.firstOrNull() == "cli-fixture") {
        val directory = Path.of(args[1]); val input = directory.resolve("input.pcm")
        val f = Fixture(directory,"hang")
        exitProcess(LinuxAudioCli.run(arguments(input),System.out,System.err) { route ->
            LinuxPcmPlayback(route,f::start,5000)
        })
    }
    if (args.firstOrNull() == "fixture") {
        val mode = args[1]; val output = Path.of(args[2]); val ready = Path.of(args[3])
        Files.writeString(ready, ProcessHandle.current().pid().toString())
        when (mode) {
            "hang" -> { Thread.sleep(60000); return }
            "nonzero" -> { System.err.print("SENSITIVE FIXTURE STDERR"); exitProcess(73) }
            "earlyzero" -> return
            "flood" -> { repeat(64) { System.err.write(ByteArray(65536)) }; System.err.flush() }
        }
        val bytes = System.`in`.readBytes()
        Files.write(output, bytes)
        if (mode == "drain-hang") Thread.sleep(60000)
        return
    }
    case("alsa argv has fixed executable, explicit device and nonblocking open") {
        val command = route.command(16000, 1)
        check(command == listOf("/usr/bin/aplay", "--quiet", "--nonblock", "--fatal-errors", "--file-type=wav", "--device=null"))
    }
    case("pipewire argv uses Trixie stdin raw syntax, no unsupported --raw flag") {
        val command = LinuxAudioRoute(LinuxAudioBackend.PIPEWIRE, "speaker.node").command(24000, 2)
        check(command.first() == "/usr/bin/pw-cat" && command.last() == "-")
        check("--rate=24000" in command && "--channels=2" in command && "--format=s16" in command)
        check("--target=speaker.node" in command && "--raw" !in command)
        check(command.any { it.contains("node.dont-fallback") && it.contains("node.dont-reconnect") })
    }
    for (device in listOf("", " ", "-x", "foo;touch", "$(echo test)", "a\nb", "../device", "a/b", "a\\b", "a\u0000b", "x".repeat(129))) {
        case("invalid route ${passed}") { refused(AudioFailure.INVALID_ROUTE) { LinuxAudioRoute(LinuxAudioBackend.ALSA, device) } }
    }
    case("named ALSA card syntax remains supported") { LinuxAudioRoute(LinuxAudioBackend.ALSA, "plughw:CARD=sndrpihifiberry,DEV=0") }
    case("pipewire disconnected target is rejected") { refused(AudioFailure.INVALID_ROUTE) { LinuxAudioRoute(LinuxAudioBackend.PIPEWIRE, "0") } }
    val invalid = listOf(Triple(ByteArray(0), 16000, 1), Triple(ByteArray(3), 16000, 1),
        Triple(ByteArray(6), 16000, 2), Triple(pcm, 7999, 1), Triple(pcm, 96001, 1),
        Triple(pcm, 16000, 0), Triple(pcm, 16000, 3), Triple(ByteArray(8*1024*1024+2), 96000, 1),
        Triple(ByteArray(8000*2*121), 8000, 1))
    for ((data, rate, channels) in invalid) case("invalid PCM ${passed}") {
        LinuxPcmPlayback(route, { error("Must not launch") }, null).use { player ->
            refused(AudioFailure.INVALID_AUDIO) { player.play(data, rate, channels) }
        }
    }
    case("ALSA receives exact canonical WAV and reports frames only after exit") {
        fixture().use { f -> LinuxPcmPlayback(route, f::start, null).use { player ->
            val result = player.play(pcm, 16000, 1)
            check(result.state == PlaybackState.COMPLETED && result.frames == 1600L && result.exitCode == 0)
            check(Files.readAllBytes(f.output).contentEquals(Pcm16Wav.wrap(pcm, 16000, 1)))
            check(!f.process.get().isAlive)
        } }
    }
    case("PipeWire receives raw PCM without WAV header") {
        fixture().use { f -> LinuxPcmPlayback(LinuxAudioRoute(LinuxAudioBackend.PIPEWIRE, "auto"), f::start, null).use { player ->
            check(player.play(pcm, 48000, 2).state == PlaybackState.COMPLETED)
            check(Files.readAllBytes(f.output).contentEquals(pcm))
        } }
    }
    case("caller mutation after admission cannot change queued bytes") {
        fixture().use { f ->
            val data = pcm.copyOf(); val expected = Pcm16Wav.wrap(data, 16000, 1)
            LinuxPcmPlayback(route, { command -> data.fill(0); f.start(command) }, null).use { p ->
                check(p.play(data, 16000, 1).state == PlaybackState.COMPLETED)
                check(Files.readAllBytes(f.output).contentEquals(expected))
            }
        }
    }
    case("backend failure is not completion and stderr is not returned") {
        fixture("nonzero").use { f -> LinuxPcmPlayback(route, f::start, null).use { p ->
            val r = p.play(pcm, 16000, 1); check(r.state == PlaybackState.FAILED)
            check(!r.toString().contains("SENSITIVE"))
        } }
    }
    case("missing executable is an explicit fixed failure") {
        LinuxPcmPlayback(route, { throw java.io.IOException("private path secret") }, null).use { p ->
            val r = p.play(pcm, 16000, 1)
            check(r.state == PlaybackState.FAILED && r.failure == AudioFailure.BACKEND_UNAVAILABLE)
            check(!r.toString().contains("secret"))
        }
    }
    case("bounded stderr cannot deadlock playback") {
        fixture("flood").use { f -> LinuxPcmPlayback(route, f::start, 5000).use { p ->
            check(p.play(pcm, 16000, 1).state == PlaybackState.COMPLETED)
        } }
    }
    case("deadline terminates a backend that never consumes the pipe") {
        fixture("hang").use { f -> LinuxPcmPlayback(route, f::start, 600).use { p ->
            val r = p.play(ByteArray(1024*1024), 48000, 1)
            check(r.state == PlaybackState.TIMED_OUT && !f.process.get().isAlive)
        } }
    }
    case("deadline also bounds backend drain after input EOF") {
        fixture("drain-hang").use { f -> LinuxPcmPlayback(route, f::start, 600).use { p ->
            val r = p.play(pcm, 16000, 1)
            check(r.state == PlaybackState.TIMED_OUT && !f.process.get().isAlive)
        } }
    }
    case("stop cancels a blocked pipe and does not poison later playback") {
        fixture("hang").use { f -> LinuxPcmPlayback(route, f::start, 5000).use { p ->
            val running = Running(p, ByteArray(1024*1024), 48000)
            waitForFile(f.ready); check(p.stop())
            check(running.finish().state == PlaybackState.CANCELLED)
            check(!f.process.get().isAlive && !p.stop())
        } }
    }
    case("concurrent call is refused rather than queued") {
        fixture("hang").use { f -> LinuxPcmPlayback(route, f::start, 5000).use { p ->
            val running = Running(p); waitForFile(f.ready)
            refused(AudioFailure.BUSY) { p.play(pcm, 16000, 1) }
            p.stop(); check(running.finish().state == PlaybackState.CANCELLED)
        } }
    }
    case("close stops active playback and rejects reuse") {
        fixture("hang").use { f ->
            val p = LinuxPcmPlayback(route, f::start, 5000)
            val running = Running(p); waitForFile(f.ready); p.close(); p.close()
            check(running.finish().state == PlaybackState.CANCELLED)
            refused(AudioFailure.CLOSED) { p.play(pcm, 16000, 1) }
        }
    }
    case("interruption cancels blocked output and preserves interrupt flag") {
        fixture("hang").use { f -> LinuxPcmPlayback(route, f::start, 5000).use { p ->
            val interrupted = AtomicReference<Boolean>(); val state = AtomicReference<PlaybackState>()
            val t = thread { state.set(p.play(ByteArray(1024*1024), 48000, 1).state); interrupted.set(Thread.currentThread().isInterrupted) }
            waitForFile(f.ready); t.interrupt(); t.join(6000)
            check(!t.isAlive && state.get() == PlaybackState.CANCELLED && interrupted.get() == true)
            check(!f.process.get().isAlive)
        } }
    }
    case("stop racing process creation sends no audio") {
        fixture().use { f ->
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            LinuxPcmPlayback(route, { args -> entered.countDown(); check(release.await(5,TimeUnit.SECONDS)); f.start(args) }, 5000).use { p ->
                val running = Running(p); check(entered.await(5,TimeUnit.SECONDS)); check(p.stop()); release.countDown()
                check(running.finish().state == PlaybackState.CANCELLED)
                check(!Files.exists(f.output) || Files.size(f.output) == 0L)
                check(!f.process.get().isAlive)
            }
        }
    }
    case("successful player is reusable") {
        fixture().use { f -> LinuxPcmPlayback(route, f::start, null).use { p ->
            repeat(3) { check(p.play(pcm, 16000, 1).state == PlaybackState.COMPLETED) }
        } }
    }
    case("private credentials and proxy settings are not inherited") {
        val pb = playerProcessBuilder(listOf("/usr/bin/aplay"), mapOf(
            "HOME" to "/home/test", "XDG_RUNTIME_DIR" to "/run/user/1000",
            "HALO_INFERENCE_API_KEY" to "secret", "HTTP_PROXY" to "secret", "JAVA_TOOL_OPTIONS" to "secret"))
        check(pb.environment()["HOME"] == "/home/test" && pb.environment()["XDG_RUNTIME_DIR"] == "/run/user/1000")
        check(pb.environment().values.none { it == "secret" })
        check(pb.redirectOutput() == ProcessBuilder.Redirect.DISCARD && pb.redirectError() == ProcessBuilder.Redirect.DISCARD)
    }
    case("a previously interrupted caller never launches a backend") {
        val result = AtomicReference<PlaybackResult>(); val retained = AtomicReference<Boolean>()
        val t = thread {
            Thread.currentThread().interrupt()
            LinuxPcmPlayback(route, { error("Must not launch") }, null).use { p ->
                result.set(p.play(pcm,16000,1)); retained.set(Thread.currentThread().isInterrupted)
            }
        }
        t.join(5000); check(!t.isAlive && result.get().state == PlaybackState.CANCELLED && retained.get() == true)
    }
    case("empty and oversized calls do not reserve the playback slot") {
        fixture().use { f -> LinuxPcmPlayback(route,f::start,null).use { p ->
            refused(AudioFailure.INVALID_AUDIO) { p.play(ByteArray(0),16000,1) }
            check(p.play(pcm,16000,1).state == PlaybackState.COMPLETED)
        } }
    }
    case("player can complete a new request after stop") {
        fixture("hang").use { first -> fixture().use { second ->
            var starts = 0
            LinuxPcmPlayback(route,{ args -> if(starts++ == 0) first.start(args) else second.start(args) },5000).use { p ->
                val r = Running(p); waitForFile(first.ready); p.stop()
                check(r.finish().state == PlaybackState.CANCELLED)
                check(p.play(pcm,16000,1).state == PlaybackState.COMPLETED && starts == 2)
            }
        } }
    }
    case("player can complete a new request after backend failure") {
        fixture("nonzero").use { first -> fixture().use { second ->
            var starts = 0
            LinuxPcmPlayback(route,{ args -> if(starts++ == 0) first.start(args) else second.start(args) },5000).use { p ->
                check(p.play(pcm,16000,1).state == PlaybackState.FAILED)
                check(p.play(pcm,16000,1).state == PlaybackState.COMPLETED)
            }
        } }
    }
    case("early zero exit cannot hide an incomplete large write") {
        fixture("earlyzero").use { f -> LinuxPcmPlayback(route,f::start,5000).use { p ->
            check(p.play(ByteArray(1024*1024),48000,1).state == PlaybackState.FAILED)
        } }
    }
    case("maximum payload is accepted when its duration fits") {
        fixture().use { f -> LinuxPcmPlayback(route,f::start,5000).use { p ->
            val bytes = ByteArray(8*1024*1024)
            val r = p.play(bytes,96000,2)
            check(r.state == PlaybackState.COMPLETED && r.frames == 2097152L)
            check(Files.size(f.output) == bytes.size.toLong()+44)
        } }
    }
    case("exact 120 second segment is accepted") {
        fixture().use { f -> LinuxPcmPlayback(route,f::start,5000).use { p ->
            check(p.play(ByteArray(8000*2*120),8000,1).state == PlaybackState.COMPLETED)
        } }
    }
    fixture().use { f ->
        val input = f.directory.resolve("input.pcm"); Files.write(input,pcm)
        val factory: (LinuxAudioRoute)->LinuxPcmPlayback = { r -> LinuxPcmPlayback(r, f::start, 5000) }
        case("CLI plays bounded PCM without mutating source") {
            val before = Files.getLastModifiedTime(input)
            val r = cli(arguments(input),factory)
            check(r.first == 0 && r.second.contains("COMPLETED") && r.third.isEmpty())
            check(Files.readAllBytes(input).contentEquals(pcm) && before == Files.getLastModifiedTime(input))
        }
        for (bad in listOf(emptyList(), listOf("play"), listOf("record"),
            arguments(input) + listOf("--rate","16000"), arguments(input) + listOf("--unknown","secret"),
            arguments(input).map { if(it=="16000") "-1" else it },
            arguments(input).map { if(it=="alsa") "automatic" else it },
            arguments(input).map { if(it==input.toString()) "relative.pcm" else it })) {
            case("CLI invalid arguments ${passed}") {
                val r = cli(bad) { error("Must not launch") }
                check(r.first == 2 && r.second.isEmpty() && !r.third.contains("secret"))
            }
        }
        case("CLI help is inert") { check(cli(listOf("--help")) { error("Must not launch") }.first == 0) }
        case("CLI rejects file symlink") {
            val link = f.directory.resolve("link"); Files.createSymbolicLink(link,input)
            check(cli(arguments(link)) { error("Must not launch") }.first == 2)
        }
        case("CLI rejects symlink ancestor") {
            val link = f.directory.resolve("linked-dir"); Files.createSymbolicLink(link,f.directory)
            check(cli(arguments(link.resolve("input.pcm"))) { error("Must not launch") }.first == 2)
        }
        case("CLI rejects directory input") { check(cli(arguments(f.directory)) { error("Must not launch") }.first == 2) }
        case("CLI rejects special file") { check(cli(arguments(Path.of("/dev/null"))) { error("Must not launch") }.first == 2) }
        case("CLI fixed error excludes private path") {
            val r = cli(arguments(f.directory.resolve("PRIVATE_MISSING_PATH"))) { error("Must not launch") }
            check(r.first == 2 && !r.third.contains("PRIVATE_MISSING_PATH"))
        }
        case("CLI rejects a WAV container passed as raw PCM") {
            val wav = f.directory.resolve("wrong-format.wav")
            Files.write(wav,checkNotNull(Pcm16Wav.wrap(pcm,16000,1)))
            val r = cli(arguments(wav),factory)
            check(r.first == 2 && r.second.isEmpty())
        }
        case("CLI rejects oversized raw input before launch") {
            val large = f.directory.resolve("large"); java.io.RandomAccessFile(large.toFile(),"rw").use { it.setLength(8L*1024*1024+1) }
            check(cli(arguments(large)) { error("Must not launch") }.first == 2)
        }
    }
    case("CLI nonzero backend maps to failure exit without diagnostics leak") {
        fixture("nonzero").use { f ->
            val input = f.directory.resolve("input.pcm"); Files.write(input,pcm)
            val r = cli(arguments(input)) { route -> LinuxPcmPlayback(route,f::start,5000) }
            check(r.first == 1 && !r.second.contains("SENSITIVE") && !r.third.contains("SENSITIVE"))
        }
    }
    case("CLI deadline has a distinct timeout exit status") {
        fixture("hang").use { f ->
            val input = f.directory.resolve("input.pcm"); Files.write(input,pcm)
            val r = cli(arguments(input)) { route -> LinuxPcmPlayback(route,f::start,600) }
            check(r.first == 124 && r.second.contains("TIMED_OUT"))
        }
    }
    case("SIGTERM of the CLI subprocess reaps its playback child") {
        fixture().use { f ->
            Files.write(f.directory.resolve("input.pcm"),ByteArray(1024*1024))
            val parent = playerProcessBuilder(listOf(javaBin,"-Xmx64m","-cp",classpath,
                "LinuxPcmPlaybackHarnessKt","cli-fixture",f.directory.toString())).start()
            var child: ProcessHandle? = null
            try {
                waitForFile(f.ready)
                child = ProcessHandle.of(Files.readString(f.ready).toLong()).orElseThrow()
                check(child.isAlive)
                parent.toHandle().destroy() // SIGTERM invokes the actual CLI shutdown hook.
                check(parent.waitFor(5,TimeUnit.SECONDS))
                check(!child.isAlive) { "CLI shutdown left the playback child running" }
            } finally {
                if (parent.isAlive) parent.toHandle().destroyForcibly()
                parent.waitFor(5,TimeUnit.SECONDS)
                child?.let { if (it.isAlive) it.destroyForcibly() }
            }
        }
    }
    case("repeated close during admission never leaves a child or playable closed instance") {
        repeat(8) {
            fixture("hang").use { f ->
                val entered = CountDownLatch(1); val release = CountDownLatch(1)
                val p = LinuxPcmPlayback(route,{ a -> entered.countDown(); check(release.await(5,TimeUnit.SECONDS)); f.start(a) },5000)
                val r = Running(p); check(entered.await(5,TimeUnit.SECONDS))
                val closer = thread { p.close() }
                Thread.sleep(5); release.countDown(); closer.join(5000)
                check(!closer.isAlive && r.finish().state == PlaybackState.CANCELLED)
                check(!f.process.get().isAlive)
                refused(AudioFailure.CLOSED) { p.play(pcm,16000,1) }
            }
        }
    }
    println("ALL PLAYBACK CASES PASSED: $passed")
}
