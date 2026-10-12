package com.halo.adapters.storage

import com.halo.libraries.common.core.Identifier
import com.halo.sdk.runtime.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Linux filesystem-based storage implementation.
 * 
 * Uses XDG Base Directory Specification for data locations,
 * with SQLite for structured data and filesystem for blobs.
 */
class LinuxStorage {
    private val log = LoggerFactory.getLogger(LinuxStorage::class.java)
    
    private lateinit var dataDir: Path
    private lateinit var configDir: Path
    private lateinit var cacheDir: Path
    private lateinit var dbPath: Path
    

    suspend fun initialize(config: LinuxStorageConfig = LinuxStorageConfig()) {
        log.info("Initializing Linux storage...")
        
        // Determine data directories using XDG spec
        val home = Paths.get(System.getProperty("user.home"))
        val xdgDataHome = config.dataDir?.let { Paths.get(it) } ?: System.getenv("XDG_DATA_HOME")?.let { Paths.get(it) } 
            ?: home.resolve(".local/share")
        val xdgConfigHome = config.configDir?.let { Paths.get(it) } ?: System.getenv("XDG_CONFIG_HOME")?.let { Paths.get(it) } 
            ?: home.resolve(".config")
        val xdgCacheHome = config.cacheDir?.let { Paths.get(it) } ?: System.getenv("XDG_CACHE_HOME")?.let { Paths.get(it) } 
            ?: home.resolve(".cache")
        
        dataDir = if (config.dataDir == null) xdgDataHome.resolve("halo-linux") else xdgDataHome
        configDir = if (config.configDir == null) xdgConfigHome.resolve("halo-linux") else xdgConfigHome
        cacheDir = if (config.cacheDir == null) xdgCacheHome.resolve("halo-linux") else xdgCacheHome
        dbPath = dataDir.resolve("halo.db")
        
        // Create directories
        Files.createDirectories(dataDir)
        Files.createDirectories(configDir)
        Files.createDirectories(cacheDir)
        Files.createDirectories(dataDir.resolve("conversations"))
        Files.createDirectories(dataDir.resolve("memories"))
        Files.createDirectories(dataDir.resolve("documents"))
        Files.createDirectories(dataDir.resolve("models"))
        Files.createDirectories(dataDir.resolve("attachments"))
        Files.createDirectories(cacheDir.resolve("inference"))
        Files.createDirectories(cacheDir.resolve("audio"))
        
        // Initialize SQLite database
        initializeDatabase()
        
        log.info("Linux storage initialized at: ${dataDir}")
    }

    private fun openConnection(): Connection =
        DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}").also { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA foreign_keys = ON")
                stmt.execute("PRAGMA busy_timeout = 5000")
            }
        }

    private fun initializeDatabase() {
        openConnection().use { conn ->
            conn.createStatement().use { it.execute("PRAGMA journal_mode = WAL") }
        }
        runMigrations()
    }

    private data class Migration(val version: Int, val description: String, val statements: List<String>)
    
    private fun runMigrations() {
        val migrations = listOf(
            Migration(1, "Core persistence and FTS5", listOf(
                "CREATE TABLE IF NOT EXISTS conversations (id TEXT PRIMARY KEY, title TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, provider_id TEXT, model_id TEXT, metadata TEXT)",
                "CREATE TABLE IF NOT EXISTS messages (id TEXT PRIMARY KEY, conversation_id TEXT NOT NULL REFERENCES conversations(id) ON DELETE CASCADE, role TEXT NOT NULL, content TEXT NOT NULL, provider_id TEXT, model_id TEXT, tokens_used INTEGER, latency_ms INTEGER, created_at TEXT NOT NULL, metadata TEXT)",
                "CREATE INDEX IF NOT EXISTS idx_messages_conversation ON messages(conversation_id)",
                "CREATE INDEX IF NOT EXISTS idx_messages_created ON messages(created_at)",
                "CREATE TABLE IF NOT EXISTS memories (id TEXT PRIMARY KEY, content TEXT NOT NULL, source TEXT, scope TEXT, confidence REAL, expires_at TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, metadata TEXT)",
                "CREATE INDEX IF NOT EXISTS idx_memories_scope ON memories(scope)",
                "CREATE INDEX IF NOT EXISTS idx_memories_expires ON memories(expires_at)",
                "CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(content, source, scope, content='memories', content_rowid='rowid')",
                "CREATE TRIGGER IF NOT EXISTS memories_ai AFTER INSERT ON memories BEGIN INSERT INTO memories_fts(rowid,content,source,scope) VALUES(new.rowid,new.content,new.source,new.scope); END",
                "CREATE TRIGGER IF NOT EXISTS memories_ad AFTER DELETE ON memories BEGIN INSERT INTO memories_fts(memories_fts,rowid,content,source,scope) VALUES('delete',old.rowid,old.content,old.source,old.scope); END",
                "CREATE TRIGGER IF NOT EXISTS memories_au AFTER UPDATE ON memories BEGIN INSERT INTO memories_fts(memories_fts,rowid,content,source,scope) VALUES('delete',old.rowid,old.content,old.source,old.scope); INSERT INTO memories_fts(rowid,content,source,scope) VALUES(new.rowid,new.content,new.source,new.scope); END"
            )),
            Migration(2, "Node identity and sync peers", listOf(
                "CREATE TABLE IF NOT EXISTS node_identity (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at TEXT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS sync_peers (id TEXT PRIMARY KEY, name TEXT, public_key TEXT NOT NULL, trust_state TEXT NOT NULL, capabilities TEXT, last_seen TEXT, created_at TEXT NOT NULL, metadata TEXT)"
            ))
        )
        openConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL, description TEXT)")
            }
            var current = conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version").use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
            migrations.filter { it.version > current }.forEach { migration ->
                conn.autoCommit = false
                try {
                    migration.statements.forEach { sql ->
                        conn.createStatement().use { stmt -> stmt.execute(sql) }
                    }
                    conn.prepareStatement("INSERT INTO schema_version (version, applied_at, description) VALUES (?, ?, ?)").use { stmt ->
                        stmt.setInt(1, migration.version)
                        stmt.setString(2, Instant.now().toString())
                        stmt.setString(3, migration.description)
                        stmt.executeUpdate()
                    }
                    conn.commit()
                    current = migration.version
                } catch (failure: Exception) {
                    conn.rollback()
                    throw IllegalStateException("SQLite migration ${migration.version} failed", failure)
                } finally {
                    conn.autoCommit = true
                }
            }
        }
    }

    suspend fun getDataDirectory(): String = withContext(Dispatchers.IO) {
        dataDir.toAbsolutePath().toString()
    }

    suspend fun getConfigDirectory(): String = withContext(Dispatchers.IO) {
        configDir.toAbsolutePath().toString()
    }

    suspend fun getCacheDirectory(): String = withContext(Dispatchers.IO) {
        cacheDir.toAbsolutePath().toString()
    }

    suspend fun storeConversation(conversation: ConversationRecord) = withContext(Dispatchers.IO) {
        openConnection().use { conn ->
            conn.setAutoCommit(false)
            try {
                conn.prepareStatement("""
                    INSERT OR REPLACE INTO conversations (id, title, created_at, updated_at, provider_id, model_id, metadata)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { stmt ->
                    stmt.setString(1, conversation.id)
                    stmt.setString(2, conversation.title)
                    stmt.setString(3, conversation.createdAt.toString())
                    stmt.setString(4, conversation.updatedAt.toString())
                    stmt.setString(5, conversation.providerId)
                    stmt.setString(6, conversation.modelId)
                    stmt.setString(7, conversation.metadata)
                    stmt.executeUpdate()
                }
                
                for (msg in conversation.messages) {
                    conn.prepareStatement("""
                        INSERT OR REPLACE INTO messages (id, conversation_id, role, content, provider_id, model_id, tokens_used, latency_ms, created_at, metadata)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent()).use { stmt ->
                        stmt.setString(1, msg.id)
                        stmt.setString(2, conversation.id)
                        stmt.setString(3, msg.role)
                        stmt.setString(4, msg.content)
                        stmt.setString(5, msg.providerId)
                        stmt.setString(6, msg.modelId)
                        stmt.setLong(7, msg.tokensUsed)
                        stmt.setLong(8, msg.latencyMs)
                        stmt.setString(9, msg.createdAt.toString())
                        stmt.setString(10, msg.metadata)
                        stmt.executeUpdate()
                    }
                }
                
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    suspend fun loadConversation(id: String): ConversationRecord? = withContext(Dispatchers.IO) {
        openConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM conversations WHERE id = ?")
                .use { stmt ->
                    stmt.setString(1, id)
                    stmt.executeQuery().use { rs ->
                        if (!rs.next()) return@use null
                        
                        val conversation = ConversationRecord(
                            id = rs.getString("id"),
                            title = rs.getString("title"),
                            createdAt = Instant.parse(rs.getString("created_at")),
                            updatedAt = Instant.parse(rs.getString("updated_at")),
                            providerId = rs.getString("provider_id"),
                            modelId = rs.getString("model_id"),
                            metadata = rs.getString("metadata"),
                            messages = emptyList(),
                        )
                        
                        val messages = mutableListOf<MessageRecord>()
                        conn.prepareStatement("SELECT * FROM messages WHERE conversation_id = ? ORDER BY created_at")
                            .use { msgStmt ->
                                msgStmt.setString(1, id)
                                msgStmt.executeQuery().use { msgRs ->
                                    while (msgRs.next()) {
                                        messages.add(MessageRecord(
                                            id = msgRs.getString("id"),
                                            conversationId = msgRs.getString("conversation_id"),
                                            role = msgRs.getString("role"),
                                            content = msgRs.getString("content"),
                                            providerId = msgRs.getString("provider_id"),
                                            modelId = msgRs.getString("model_id"),
                                            tokensUsed = msgRs.getLong("tokens_used"),
                                            latencyMs = msgRs.getLong("latency_ms"),
                                            createdAt = Instant.parse(msgRs.getString("created_at")),
                                            metadata = msgRs.getString("metadata"),
                                        ))
                                    }
                                }
                            }
                        
                        conversation.copy(messages = messages)
                    }
                }
        }
    }

    suspend fun listConversations(limit: Int = 50, offset: Int = 0): List<ConversationSummary> = withContext(Dispatchers.IO) {
        openConnection().use { conn ->
            conn.prepareStatement("SELECT id, title, created_at, updated_at, provider_id, model_id FROM conversations ORDER BY updated_at DESC LIMIT ? OFFSET ?")
                .use { stmt ->
                    stmt.setInt(1, limit)
                    stmt.setInt(2, offset)
                    stmt.executeQuery().use { rs ->
                        val results = mutableListOf<ConversationSummary>()
                        while (rs.next()) {
                            results.add(ConversationSummary(
                                id = rs.getString("id"),
                                title = rs.getString("title"),
                                createdAt = Instant.parse(rs.getString("created_at")),
                                updatedAt = Instant.parse(rs.getString("updated_at")),
                                providerId = rs.getString("provider_id"),
                                modelId = rs.getString("model_id"),
                            ))
                        }
                        results
                    }
                }
        }
    }

    suspend fun searchMemories(query: String, limit: Int = 20): List<MemoryRecord> = withContext(Dispatchers.IO) {
        openConnection().use { conn ->
            conn.prepareStatement("""
                SELECT m.* FROM memories m
                JOIN memories_fts f ON m.rowid = f.rowid
                WHERE memories_fts MATCH ?
                ORDER BY rank
                LIMIT ?
            """.trimIndent()).use { stmt ->
                stmt.setString(1, query)
                stmt.setInt(2, limit)
                stmt.executeQuery().use { rs ->
                    val results = mutableListOf<MemoryRecord>()
                    while (rs.next()) {
                        results.add(MemoryRecord(
                            id = rs.getString("id"),
                            content = rs.getString("content"),
                            source = rs.getString("source"),
                            scope = rs.getString("scope"),
                            confidence = rs.getDouble("confidence"),
                            expiresAt = rs.getString("expires_at")?.let { Instant.parse(it) },
                            createdAt = Instant.parse(rs.getString("created_at")),
                            updatedAt = Instant.parse(rs.getString("updated_at")),
                            metadata = rs.getString("metadata"),
                        ))
                    }
                    results
                }
            }
        }
    }

    suspend fun storeMemory(memory: MemoryRecord) = withContext(Dispatchers.IO) {
        openConnection().use { conn ->
            conn.prepareStatement("""
                INSERT OR REPLACE INTO memories (id, content, source, scope, confidence, expires_at, created_at, updated_at, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { stmt ->
                stmt.setString(1, memory.id)
                stmt.setString(2, memory.content)
                stmt.setString(3, memory.source)
                stmt.setString(4, memory.scope)
                stmt.setDouble(5, memory.confidence)
                stmt.setString(6, memory.expiresAt?.toString())
                stmt.setString(7, memory.createdAt.toString())
                stmt.setString(8, memory.updatedAt.toString())
                stmt.setString(9, memory.metadata)
                stmt.executeUpdate()
            }
        }
    }

    suspend fun getNodeIdentity(): NodeIdentity = withContext(Dispatchers.IO) {
        openConnection().use { conn ->
            val map = mutableMapOf<String, String>()
            conn.createStatement()
                .executeQuery("SELECT key, value FROM node_identity")
                .use { rs ->
                    while (rs.next()) {
                        map[rs.getString("key")] = rs.getString("value")
                    }
                }
            
            val nodeId = map["node_id"] ?: UUID.randomUUID().toString()
            val displayName = map["display_name"] ?: "Halo Linux Node"
            val publicKey = map["public_key"] ?: ""
            val privateKey = map["private_key"] ?: ""
            
            // Persist if new
            if (!map.containsKey("node_id")) {
                conn.prepareStatement("INSERT INTO node_identity (key, value, updated_at) VALUES (?, ?, ?)")
                    .use { stmt ->
                        stmt.setString(1, "node_id")
                        stmt.setString(2, nodeId)
                        stmt.setString(3, Instant.now().toString())
                        stmt.executeUpdate()
                    }
            }
            
            NodeIdentity(
                nodeId = nodeId,
                displayName = displayName,
                publicKey = publicKey,
                privateKey = privateKey,
                protocolVersion = 1,
                softwareVersion = "0.1.0-SNAPSHOT",
                capabilities = listOf("inference", "memory", "search", "audio-diagnostics"),
                trustState = "unpaired",
            )
        }
    }

    suspend fun setNodeIdentity(identity: NodeIdentity) = withContext(Dispatchers.IO) {
        openConnection().use { conn ->
            conn.setAutoCommit(false)
            try {
                val entries = mapOf(
                    "node_id" to identity.nodeId,
                    "display_name" to identity.displayName,
                    "public_key" to identity.publicKey,
                    "private_key" to identity.privateKey,
                )
                
                for ((key, value) in entries) {
                    conn.prepareStatement("INSERT OR REPLACE INTO node_identity (key, value, updated_at) VALUES (?, ?, ?)")
                        .use { stmt ->
                            stmt.setString(1, key)
                            stmt.setString(2, value)
                            stmt.setString(3, Instant.now().toString())
                            stmt.executeUpdate()
                        }
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        log.info("Closing Linux storage")
        // HikariCP would be used in production for connection pooling
    }
}

/**
 * Configuration for Linux storage.
 */
data class LinuxStorageConfig(
    val dataDir: String? = null,
    val configDir: String? = null,
    val cacheDir: String? = null,
)

/**
 * Conversation record with messages.
 */
data class ConversationRecord(
    val id: String,
    val title: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val providerId: String?,
    val modelId: String?,
    val metadata: String?,
    val messages: List<MessageRecord> = emptyList(),
)

/**
 * Individual message in a conversation.
 */
data class MessageRecord(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val providerId: String?,
    val modelId: String?,
    val tokensUsed: Long,
    val latencyMs: Long,
    val createdAt: Instant,
    val metadata: String?,
)

/**
 * Lightweight conversation summary for listings.
 */
data class ConversationSummary(
    val id: String,
    val title: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val providerId: String?,
    val modelId: String?,
)

/**
 * Memory record for personal knowledge.
 */
data class MemoryRecord(
    val id: String,
    val content: String,
    val source: String?,
    val scope: String?,
    val confidence: Double,
    val expiresAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val metadata: String?,
)

/**
 * Persistent node identity for sync.
 */
data class NodeIdentity(
    val nodeId: String,
    val displayName: String,
    val publicKey: String,
    val privateKey: String,
    val protocolVersion: Int,
    val softwareVersion: String,
    val capabilities: List<String>,
    val trustState: String,
)