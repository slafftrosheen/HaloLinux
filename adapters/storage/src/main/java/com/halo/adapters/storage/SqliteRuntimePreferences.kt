package com.halo.adapters.storage

import com.halo.sdk.runtime.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

/**
 * Simple SQLite-backed key-value preferences storage.
 * 
 * Implements the synchronous [Storage] interface for runtime preferences.
 * Uses a single preferences table with type-specific columns.
 */
class SqliteRuntimePreferences : Storage {
    private lateinit var dbPath: java.nio.file.Path
    private lateinit var connection: Connection

    /**
     * Initialize the preferences database at the given path.
     * @param dbPath Path to the SQLite database file
     */
    suspend fun initialize(dbPath: java.nio.file.Path) = withContext(Dispatchers.IO) {
        this.dbPath = dbPath
        java.nio.file.Files.createDirectories(dbPath.parent)
        
        connection = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}?foreign_keys=on")
        connection.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS preferences (
                key TEXT PRIMARY KEY,
                string_value TEXT,
                int_value INTEGER,
                boolean_value INTEGER,
                updated_at TEXT NOT NULL
            )
        """.trimIndent())
    }

    private fun getStringInternal(key: String): String? = connection.prepareStatement(
        "SELECT string_value FROM preferences WHERE key = ?"
    ).use { stmt ->
        stmt.setString(1, key)
        stmt.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    }

    private fun getIntInternal(key: String): Int? = connection.prepareStatement(
        "SELECT int_value FROM preferences WHERE key = ?"
    ).use { stmt ->
        stmt.setString(1, key)
        stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1).takeIf { !rs.wasNull() } else null }
    }

    private fun getBooleanInternal(key: String): Boolean? = connection.prepareStatement(
        "SELECT boolean_value FROM preferences WHERE key = ?"
    ).use { stmt ->
        stmt.setString(1, key)
        stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1).takeIf { !rs.wasNull() }?.let { it != 0 } else null }
    }

    override fun getString(key: String, defaultValue: String? = null): String? = getStringInternal(key) ?: defaultValue
    override fun putString(key: String, value: String?) = withContext(Dispatchers.IO) {
        val now = java.time.Instant.now().toString()
        connection.prepareStatement("""
            INSERT INTO preferences (key, string_value, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET string_value = excluded.string_value, updated_at = excluded.updated_at
        """.trimIndent()).use { stmt ->
            stmt.setString(1, key)
            stmt.setString(2, value.orEmpty())
            stmt.setString(3, now)
            stmt.executeUpdate()
        }
    }

    override fun getInt(key: String, defaultValue: Int = 0): Int = getIntInternal(key) ?: defaultValue
    override fun putInt(key: String, value: Int) = withContext(Dispatchers.IO) {
        val now = java.time.Instant.now().toString()
        connection.prepareStatement("""
            INSERT INTO preferences (key, int_value, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET int_value = excluded.int_value, updated_at = excluded.updated_at
        """.trimIndent()).use { stmt ->
            stmt.setString(1, key)
            stmt.setInt(2, value)
            stmt.setString(3, now)
            stmt.executeUpdate()
        }
    }

    override fun getBoolean(key: String, defaultValue: Boolean = false): Boolean = getBooleanInternal(key) ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) = withContext(Dispatchers.IO) {
        val now = java.time.Instant.now().toString()
        connection.prepareStatement("""
            INSERT INTO preferences (key, boolean_value, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(key) DO UPDATE SET boolean_value = excluded.boolean_value, updated_at = excluded.updated_at
        """.trimIndent()).use { stmt ->
            stmt.setString(1, key)
            stmt.setInt(2, if (value) 1 else 0)
            stmt.setString(3, now)
            stmt.executeUpdate()
        }
    }

    override fun putBatch(
        strings: Map<String, String?> = emptyMap(),
        booleans: Map<String, Boolean> = emptyMap(),
    ) = withContext(Dispatchers.IO) {
        connection.autoCommit = false
        try {
            val now = java.time.Instant.now().toString()
            strings.forEach { (key, value) ->
                connection.prepareStatement("""
                    INSERT INTO preferences (key, string_value, updated_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT(key) DO UPDATE SET string_value = excluded.string_value, updated_at = excluded.updated_at
                """.trimIndent()).use { stmt ->
                    stmt.setString(1, key)
                    stmt.setString(2, value.orEmpty())
                    stmt.setString(3, now)
                    stmt.executeUpdate()
                }
            }
            booleans.forEach { (key, value) ->
                connection.prepareStatement("""
                    INSERT INTO preferences (key, boolean_value, updated_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT(key) DO UPDATE SET boolean_value = excluded.boolean_value, updated_at = excluded.updated_at
                """.trimIndent()).use { stmt ->
                    stmt.setString(1, key)
                    stmt.setInt(2, if (value) 1 else 0)
                    stmt.setString(3, now)
                    stmt.executeUpdate()
                }
            }
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    override fun remove(key: String) = withContext(Dispatchers.IO) {
        connection.prepareStatement("DELETE FROM preferences WHERE key = ?").use { stmt ->
            stmt.setString(1, key)
            stmt.executeUpdate()
        }
    }

    override fun clear() = withContext(Dispatchers.IO) {
        connection.prepareStatement("DELETE FROM preferences").executeUpdate()
    }
}