package com.halo.host.linux

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class NodeConfigTest {
    @TempDir lateinit var home: Path

    @Test fun acceptsTrixieLocalServer() {
        val config = NodeConfiguration.parse(
            """
            node:
              display_name: "Halo Pi 5"
            inference:
              endpoint: "http://127.0.0.1:11434/v1"
              model: "qwen2.5:1.5b"
            storage:
              data_dir: "~/halo-data"
            audio:
              enabled: false
            """.trimIndent(), home
        )
        assertEquals("Halo Pi 5", config.displayName)
        assertTrue(config.providerConfigured)
        assertEquals(home.resolve("halo-data").toString(), config.dataDir)
    }

    @Test fun rejectsPlaintextLanAndUrlCredentials() {
        val lan = "inference:\n  endpoint: http://192.168.1.55:11434/v1\n  model: foo"
        assertThrows(IllegalArgumentException::class.java) {
            NodeConfiguration.parse(lan, home)
        }
        val embedded = "inference:\n  endpoint: https://user:secret@example.org/v1\n  model: foo"
        assertThrows(IllegalArgumentException::class.java) {
            NodeConfiguration.parse(embedded, home)
        }
        val query = "inference:\n  endpoint: https://example.org/v1?key=secret\n  model: foo"
        assertThrows(IllegalArgumentException::class.java) {
            NodeConfiguration.parse(query, home)
        }
    }

    @Test fun noProviderIsLegalForMemoryOnlyOperation() {
        val config = NodeConfiguration.parse("node: {name: Offline node}", home)
        assertFalse(config.providerConfigured)
        assertEquals("Offline node", config.displayName)
    }

    @Test fun rejectsOversizedAndMalformedYaml() {
        assertThrows(IllegalArgumentException::class.java) {
            NodeConfiguration.parse(" ".repeat(100_001), home)
        }
        assertThrows(Exception::class.java) {
            NodeConfiguration.parse("node: [invalid map]", home)
        }
    }
    @Test fun exampleConfigIsModelFreeByDefault() {
        val source = """
            node:
              display_name: "Pi Desktop Node"
            inference:
              # endpoint: "http://127.0.0.1:11434/v1"
              # model: "replace-with-actually-installed-model"
            audio:
              enabled: false
        """.trimIndent()
        val config = NodeConfiguration.parse(source, home)
        assertFalse(config.providerConfigured)
        assertNull(config.providerEndpoint)
        assertNull(config.providerModel)
    }

}
