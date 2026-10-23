package com.halo.adapters.storage

import com.halo.sdk.runtime.Storage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Linux host adapter for the unchanged canonical Storage contract.
 *
 * One checksummed, versioned snapshot per already-private XDG configuration
 * directory; a persistent sidecar locks across JVMs. Mutations use fsync and
 * atomic replacement. Does not open or migrate halo.db.
 *
 * This is synchronous filesystem I/O: use outside latency-critical threads.
 * Files contain plaintext state. It is not an encryption or secure-delete tool.
 */
class LinuxPreferenceStorage(directory: Path) : Storage {
    private sealed interface Value {
        data object NullString : Value
        data class StringValue(val value: String) : Value
        data class IntegerValue(val value: Int) : Value
        data class BooleanValue(val value: Boolean) : Value
    }

    private val root: Path
    private val data: Path
    private val sentinel: Path

    /** Test-only injection point; never configure a production callback. */
    internal var beforeReplace: (() -> Unit)? = null

    init {
        require(directory.isAbsolute && directory.none { it.toString() == ".." }) {
            "Preferences require an absolute private directory."
        }
        root = directory.normalize()
        data = root.resolve(DATA_NAME)
        sentinel = root.resolve(LOCK_NAME)
        privateDirectory()
    }

    private fun privateDirectory() {
        var current = root.root ?: throw IllegalArgumentException("Invalid preferences directory.")
        for (part in root) {
            current = current.resolve(part)
            require(!Files.isSymbolicLink(current)) { "Preferences directory contains a symbolic link." }
        }
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            "Preferences directory does not exist."
        }
        require(Files.getOwner(root, LinkOption.NOFOLLOW_LINKS).name == System.getProperty("user.name") &&
            Files.getPosixFilePermissions(root) == OWNER_DIRECTORY) {
            "Preferences directory must be owned by this user with mode 0700."
        }
    }

    private fun validateKey(key: String) {
        val bytes = key.toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_KEY_BYTES && key.isNotBlank() &&
            key.none { it.code < 32 || it.code == 127 } && bytes.toString(Charsets.UTF_8) == key) {
            "Invalid preference key or key size."
        }
    }

    private fun validateString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_VALUE_BYTES && bytes.toString(Charsets.UTF_8) == value) {
            "Preference string exceeds supported size or encoding."
        }
    }

    private fun inspectFile(path: Path) {
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
            Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).name == System.getProperty("user.name") &&
            Files.getPosixFilePermissions(path) == OWNER_FILE &&
            (Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toLong() == 1L) {
            "Preferences file must be a private, single-linked regular file."
        }
    }

    private fun ensureLockFile() {
        try {
            Files.createFile(sentinel, PosixFilePermissions.asFileAttribute(OWNER_FILE))
        } catch (_: FileAlreadyExistsException) {
            // A retained lock inode is intentional: never delete it after unlock.
        }
        inspectFile(sentinel)
    }

    private fun <T> locked(action: () -> T): T {
        privateDirectory()
        val jvmLock = JVM_LOCKS.computeIfAbsent(root) { ReentrantLock() }
        jvmLock.lockInterruptibly()
        try {
            ensureLockFile()
            FileChannel.open(sentinel, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val started = System.nanoTime()
                var held: FileLock? = null
                while (held == null) {
                    try {
                        held = channel.tryLock()
                    } catch (_: OverlappingFileLockException) {
                        // Another classloader might use the same stable lock inode.
                    }
                    if (held == null) {
                        if (System.nanoTime() - started > MAX_LOCK_WAIT_NS) throw IOException("Preferences store is busy.")
                        Thread.sleep(5)
                    }
                }
                held.use {
                    privateDirectory()
                    inspectFile(sentinel)
                    return action()
                }
            }
        } finally {
            jvmLock.unlock()
        }
    }

    private fun read(): LinkedHashMap<String, Value> {
        if (!Files.exists(data, LinkOption.NOFOLLOW_LINKS)) return LinkedHashMap()
        inspectFile(data)
        val bytes = FileChannel.open(data, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            if (size < MAGIC.size + 8L + DIGEST_BYTES || size > MAX_FILE_BYTES) throw IOException("Invalid preferences store size.")
            val buffer = ByteBuffer.allocate(size.toInt())
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) throw IOException("Incomplete preferences store.")
            }
            buffer.array()
        }
        val authenticatedSize = bytes.size - DIGEST_BYTES
        val payload = bytes.copyOfRange(0, authenticatedSize)
        val expected = bytes.copyOfRange(authenticatedSize, bytes.size)
        if (!MessageDigest.isEqual(expected, sha256(payload))) throw IOException("Invalid preferences store checksum.")
        try {
            val result = LinkedHashMap<String, Value>()
            DataInputStream(ByteArrayInputStream(payload)).use { stream ->
                val header = ByteArray(MAGIC.size)
                stream.readFully(header)
                if (!header.contentEquals(MAGIC) || stream.readInt() != FORMAT_VERSION) {
                    throw IOException("Unsupported preferences format.")
                }
                val count = stream.readInt()
                if (count !in 0..MAX_ENTRIES) throw IOException("Invalid preferences entry count.")
                repeat(count) {
                    val key = utf8(stream.readNBytes(stream.readUnsignedShort()))
                    validateKey(key)
                    if (result.containsKey(key)) throw IOException("Duplicate preference key.")
                    val value = when (stream.readUnsignedByte()) {
                        0 -> Value.NullString
                        1 -> {
                            val length = stream.readInt()
                            if (length !in 0..MAX_VALUE_BYTES) throw IOException("Invalid preference value size.")
                            Value.StringValue(utf8(stream.readNBytes(length)))
                        }
                        2 -> Value.IntegerValue(stream.readInt())
                        3 -> when (stream.readUnsignedByte()) {
                            0 -> Value.BooleanValue(false)
                            1 -> Value.BooleanValue(true)
                            else -> throw IOException("Invalid preference boolean.")
                        }
                        else -> throw IOException("Unsupported preference type.")
                    }
                    result[key] = value
                }
                if (stream.available() != 0) throw IOException("Unexpected preferences store suffix.")
            }
            return result
        } catch (_: Exception) {
            // Inputs are not logged or included in exception details.
            throw IOException("Malformed preferences store; preserve it for recovery.")
        }
    }

    private fun utf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        throw IOException("Invalid UTF-8 in preferences store.")
    }

    private fun write(entries: Map<String, Value>) {
        require(entries.size <= MAX_ENTRIES) { "Preference entry limit exceeded." }
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { stream ->
            stream.write(MAGIC)
            stream.writeInt(FORMAT_VERSION)
            stream.writeInt(entries.size)
            entries.toSortedMap().forEach { (key, value) ->
                validateKey(key)
                val keyBytes = key.toByteArray(Charsets.UTF_8)
                stream.writeShort(keyBytes.size)
                stream.write(keyBytes)
                when (value) {
                    Value.NullString -> stream.writeByte(0)
                    is Value.StringValue -> {
                        validateString(value.value)
                        val encoded = value.value.toByteArray(Charsets.UTF_8)
                        stream.writeByte(1)
                        stream.writeInt(encoded.size)
                        stream.write(encoded)
                    }
                    is Value.IntegerValue -> { stream.writeByte(2); stream.writeInt(value.value) }
                    is Value.BooleanValue -> { stream.writeByte(3); stream.writeByte(if (value.value) 1 else 0) }
                }
                require(buffer.size() + DIGEST_BYTES <= MAX_FILE_BYTES) { "Preferences total size limit exceeded." }
            }
        }
        val dataBytes = buffer.toByteArray()
        val outputBytes = dataBytes + sha256(dataBytes)
        require(outputBytes.size.toLong() <= MAX_FILE_BYTES) { "Preferences total size limit exceeded." }
        if (Files.exists(data, LinkOption.NOFOLLOW_LINKS)) inspectFile(data)
        // Create with 0600 at creation, before writing any plaintext.
        val pending = Files.createTempFile(root, ".halo-prefs-", ".pending",
            PosixFilePermissions.asFileAttribute(OWNER_FILE))
        try {
            FileChannel.open(pending, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val view = ByteBuffer.wrap(outputBytes)
                while (view.hasRemaining()) channel.write(view)
                channel.force(true)
            }
            beforeReplace?.invoke()
            if (Files.exists(data, LinkOption.NOFOLLOW_LINKS)) inspectFile(data)
            Files.move(pending, data, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            // Linux filesystems that support atomic rename also allow a directory
            // fsync; failure here must not be silently called durable.
            FileChannel.open(root, StandardOpenOption.READ).use { it.force(true) }
        } finally {
            Files.deleteIfExists(pending)
        }
    }

    private fun <T> query(block: (Map<String, Value>) -> T): T = locked { block(read()) }
    private fun mutate(block: (MutableMap<String, Value>) -> Unit) = locked {
        val original = read()
        val changed = LinkedHashMap(original)
        block(changed)
        if (changed != original) write(changed)
    }

    override fun getString(key: String, defaultValue: String?): String? {
        validateKey(key)
        return query { values -> when (val value = values[key]) {
            null -> defaultValue
            Value.NullString -> null
            is Value.StringValue -> value.value
            else -> throw IllegalStateException("Preference type mismatch.")
        } }
    }
    override fun getInt(key: String, defaultValue: Int): Int {
        validateKey(key)
        return query { values -> when (val value = values[key]) {
            null -> defaultValue
            is Value.IntegerValue -> value.value
            else -> throw IllegalStateException("Preference type mismatch.")
        } }
    }
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean {
        validateKey(key)
        return query { values -> when (val value = values[key]) {
            null -> defaultValue
            is Value.BooleanValue -> value.value
            else -> throw IllegalStateException("Preference type mismatch.")
        } }
    }
    override fun putString(key: String, value: String?) {
        validateKey(key)
        if (value != null) validateString(value)
        mutate { it[key] = if (value == null) Value.NullString else Value.StringValue(value) }
    }
    override fun putInt(key: String, value: Int) { validateKey(key); mutate { it[key] = Value.IntegerValue(value) } }
    override fun putBoolean(key: String, value: Boolean) { validateKey(key); mutate { it[key] = Value.BooleanValue(value) } }
    override fun putBatch(strings: Map<String, String?>, booleans: Map<String, Boolean>) {
        require(strings.keys.intersect(booleans.keys).isEmpty()) { "Duplicate preference key in batch." }
        strings.forEach { (key, value) -> validateKey(key); if (value != null) validateString(value) }
        booleans.keys.forEach(::validateKey)
        if (strings.isEmpty() && booleans.isEmpty()) return
        mutate { values ->
            strings.forEach { (key, value) -> values[key] = if (value == null) Value.NullString else Value.StringValue(value) }
            booleans.forEach { (key, value) -> values[key] = Value.BooleanValue(value) }
        }
    }
    override fun remove(key: String) { validateKey(key); mutate { it.remove(key) } }
    override fun clear() = mutate { it.clear() }

    private companion object {
        const val DATA_NAME = "preferences.v1"
        const val LOCK_NAME = "preferences.lock"
        const val FORMAT_VERSION = 1
        const val MAX_KEY_BYTES = 128
        const val MAX_VALUE_BYTES = 65_536
        const val MAX_ENTRIES = 2048
        const val MAX_FILE_BYTES = 1024 * 1024L
        const val DIGEST_BYTES = 32
        const val MAX_LOCK_WAIT_NS = 10_000_000_000L
        val MAGIC = "HALOPREF".toByteArray(Charsets.US_ASCII)
        val OWNER_FILE = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        val OWNER_DIRECTORY = OWNER_FILE + PosixFilePermission.OWNER_EXECUTE
        val JVM_LOCKS = ConcurrentHashMap<Path, ReentrantLock>()
        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
