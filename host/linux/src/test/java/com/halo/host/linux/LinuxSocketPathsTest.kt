package com.halo.host.linux

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LinuxSocketPathsTest {
    @TempDir lateinit var temp: Path

    @Test fun fallbackCreatesPrivateSocketDirectory() {
        val result = LinuxSocketPaths.socket(temp, null)
        assertTrue(result.endsWith("halo-linux/node.sock"))
        assertFalse(Files.exists(result))
        assertEquals(setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
        ), Files.getPosixFilePermissions(result.parent))
    }

    @Test fun symlinkedRuntimeDirectoryIsRejectedWithoutCreatingSocket() {
        val target = temp.resolve("target")
        Files.createDirectories(target)
        val redirect = temp.resolve("redirect")
        Files.createSymbolicLink(redirect, target)
        assertThrows(IllegalArgumentException::class.java) {
            LinuxSocketPaths.socket(temp, redirect)
        }
        assertFalse(Files.exists(target.resolve("halo-linux")))
    }

    @Test fun sharedOrRelativeXdgRuntimeDirectoryIsRejected() {
        val shared = temp.resolve("shared")
        Files.createDirectory(shared)
        Files.setPosixFilePermissions(shared, setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.OTHERS_EXECUTE
        ))
        assertThrows(IllegalArgumentException::class.java) {
            LinuxSocketPaths.socket(temp, shared)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LinuxSocketPaths.socket(temp, Path.of("relative/runtime"))
        }
    }
}
