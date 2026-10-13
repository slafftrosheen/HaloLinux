package com.halo.host.linux

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Unix-socket path resolution. Do not create/chmod an untrusted runtime tree
 * before proving it is a real private directory without symlink ancestors.
 */
internal object LinuxSocketPaths {
    private val ownerOnly = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )

    private fun checkAncestors(path: Path) {
        require(path.isAbsolute) { "Runtime directory must be absolute" }
        var cursor = path.root ?: throw IllegalArgumentException("No runtime root")
        for (part in path.normalize()) {
            cursor = cursor.resolve(part)
            require(!Files.isSymbolicLink(cursor)) {
                "Runtime directory contains a symbolic link"
            }
        }
    }

    fun socket(home: Path, xdgRuntime: Path?): Path {
        val runtime = if (xdgRuntime == null) {
            home.resolve(".local/state/halo-linux/run")
        } else xdgRuntime
        require(runtime.isAbsolute) { "XDG_RUNTIME_DIR must be absolute" }
        val root = runtime.normalize()
        checkAncestors(root)
        if (xdgRuntime == null) {
            Files.createDirectories(root)
            checkAncestors(root)
            Files.setPosixFilePermissions(root, ownerOnly)
        } else {
            require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                "XDG_RUNTIME_DIR must already exist"
            }
            require(Files.getPosixFilePermissions(root) == ownerOnly) {
                "XDG_RUNTIME_DIR must be a private 0700 directory"
            }
        }
        val nodeDir = root.resolve("halo-linux")
        checkAncestors(nodeDir)
        if (!Files.exists(nodeDir, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(
                nodeDir,
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(ownerOnly)
            )
        }
        checkAncestors(nodeDir)
        require(Files.isDirectory(nodeDir, LinkOption.NOFOLLOW_LINKS)) {
            "Halo runtime socket directory must be a real directory"
        }
        Files.setPosixFilePermissions(nodeDir, ownerOnly)
        return nodeDir.resolve("node.sock")
    }
}
