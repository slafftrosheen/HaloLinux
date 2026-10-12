package com.halo.host.linux

import com.halo.adapters.storage.LinuxStorage
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Creates an owner-private, verified snapshot set for both local databases.
 * Must run behind the HaloCli mutation gate so no in-process user writes can
 * straddle the two independent SQLite VACUUM snapshots.
 *
 * The set is consistent with respect to daemon-initiated mutations, not an
 * atomic SQLite cross-database transaction and not an encrypted backup.
 */
internal object LinuxNodeSnapshot {
    private val dirPermissions = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )
    private val filePermissions = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
    )

    suspend fun create(
        destination: Path, storage: LinuxStorage, recovery: LinuxRecoverySupport,
    ): JSONObject = withContext(Dispatchers.IO) {
        require(destination.isAbsolute) { "Backup set requires an absolute path" }
        val target = destination.normalize()
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            "Backup destination already exists"
        }
        val parent = target.parent ?: throw IllegalArgumentException("Missing backup parent")
        require(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(parent)) { "Backup parent is invalid" }
        require(Files.getPosixFilePermissions(parent).none {
            it in setOf(
                PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE
            )
        }) { "Backup parent must be private (0700)" }

        Files.createDirectory(
            target,
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(dirPermissions)
        )
        val main = target.resolve("halo-main.sqlite")
        val agent = target.resolve("halo-agent.sqlite")
        val manifest = target.resolve("manifest.json")
        try {
            val mainReceipt = storage.createBackup(main)
            val records = JSONArray().put(fileRecord(main, "halo-main", mainReceipt.bytes))
            val linked = recovery.inspect().optString("state") != "NOT_LINKED"
            if (linked) {
                val receipt = recovery.backup(agent)
                require(receipt.optBoolean("ok") && Files.isRegularFile(agent)) {
                    "Canonical agent journal backup failed"
                }
                records.put(fileRecord(agent, "canonical-agent", Files.size(agent)))
            }
            val doc = JSONObject()
                .put("format", "halo-node-snapshot-v1")
                .put("created_at", Instant.now().toString())
                .put("node_id", storage.getNodeIdentity().nodeId)
                .put("includes_canonical_agent_store", linked)
                .put("files", records)
                .put("sensitive", true)
                .put("encrypted", false)
            Files.writeString(manifest, doc.toString(2),
                java.nio.file.StandardOpenOption.CREATE_NEW)
            Files.setPosixFilePermissions(manifest, filePermissions)
            JSONObject().put("ok", true).put("path", target.toString())
                .put("files", records.length())
                .put("contains_private_data", true)
                .put("encrypted", false)
        } catch (error: Exception) {
            // The new directory is owned exclusively by this operation. No
            // existing user paths are deleted and no snapshot is overwritten.
            for (file in listOf(manifest, agent, main)) {
                runCatching { Files.deleteIfExists(file) }
            }
            runCatching { Files.deleteIfExists(target) }
            throw error
        }
    }

    private fun fileRecord(path: Path, role: String, bytes: Long): JSONObject {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        val checksum = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return JSONObject().put("role", role).put("filename", path.fileName.toString())
            .put("bytes", bytes).put("sha256", checksum)
    }
}
