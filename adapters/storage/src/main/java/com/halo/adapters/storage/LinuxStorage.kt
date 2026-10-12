package com.halo.adapters.storage

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
        // Sensitive notes, credentials and metadata stay private even under umask 022.
        val ownerOnly = setOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
        )
        for (dir in listOf(dataDir, configDir, cacheDir)) {
            Files.setPosixFilePermissions(dir, ownerOnly)
        }
        Files.createDirectories(dataDir.resolve("conversations"))
        Files.createDirectories(dataDir.resolve("memories"))
        Files.createDirectories(dataDir.resolve("documents"))
        Files.createDirectories(dataDir.resolve("models"))
        Files.createDirectories(dataDir.resolve("attachments"))
        Files.createDirectories(cacheDir.resolve("inference"))
        Files.createDirectories(cacheDir.resolve("audio"))
        // Directory traversal is blocked even if SQLite subsequently creates WAL/SHM files
        // with host-default file permissions.
        (listOf(dataDir, configDir, cacheDir) +
            listOf("conversations", "memories", "documents", "models", "attachments").map(dataDir::resolve) +
            listOf("inference", "audio").map(cacheDir::resolve)).forEach { directory ->
            Files.setPosixFilePermissions(directory, setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
            ))
        }

        // Initialize SQLite database
        initializeDatabase()
        Files.setPosixFilePermissions(dbPath, setOf(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
        ))

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
            )),
            Migration(3, "Conservative text-turn checkpoint ledger", listOf(
                "CREATE TABLE IF NOT EXISTS turn_checkpoints (interaction_id TEXT PRIMARY KEY, state TEXT NOT NULL CHECK(state IN ('ACCEPTED','REASONING','COMMITTED','FAILED','INTERRUPTED')), created_at TEXT NOT NULL, updated_at TEXT NOT NULL, conversation_id TEXT, failure_type TEXT)",
                "CREATE INDEX IF NOT EXISTS idx_turn_checkpoints_state ON turn_checkpoints(state)"
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

    /**
     * Private DB helper shared by independent conversation saves and atomic
     * cognitive commits. Never exposes an uncommitted answer as completed.
     */
    private fun writeConversation(conn: Connection, conversation: ConversationRecord) {
                conn.prepareStatement("""
                    INSERT INTO conversations (id, title, created_at, updated_at, provider_id, model_id, metadata)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(id) DO UPDATE SET
                        title=excluded.title, updated_at=excluded.updated_at,
                        provider_id=excluded.provider_id, model_id=excluded.model_id,
                        metadata=excluded.metadata
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
                        INSERT INTO messages (id, conversation_id, role, content, provider_id, model_id, tokens_used, latency_ms, created_at, metadata)
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
                
    }

    suspend fun storeConversation(conversation: ConversationRecord) = withContext(Dispatchers.IO) {
        openConnection().use { conn ->
            conn.autoCommit = false
            try {
                writeConversation(conn, conversation)
                conn.commit()
            } catch (error: Exception) {
                conn.rollback()
                throw error
            } finally {
                conn.autoCommit = true
            }
        }
    }

    /**
     * Exactly-once local completion: conversation and journal state advance in
     * one SQLite transaction, conditional on REASONING. A replay/duplicate
     * cannot overwrite the original conversation.
     */
    suspend fun commitTextTurn(interactionId: String, conversation: ConversationRecord) =
        withContext(Dispatchers.IO) {
            require(interactionId.isNotBlank() && interactionId.length <= 128)
            require(conversation.id.isNotBlank())
            require(conversation.messages.isNotEmpty())
            openConnection().use { conn ->
                conn.autoCommit = false
                try {
                    val claimed = conn.prepareStatement("""
                        UPDATE turn_checkpoints
                        SET state='COMMITTED', updated_at=?, conversation_id=?, failure_type=NULL
                        WHERE interaction_id=? AND state='REASONING'
                    """.trimIndent()).use { statement ->
                        statement.setString(1, Instant.now().toString())
                        statement.setString(2, conversation.id)
                        statement.setString(3, interactionId)
                        statement.executeUpdate()
                    }
                    check(claimed == 1) { "Text turn was already committed or is not reasoning" }
                    writeConversation(conn, conversation)
                    conn.commit()
                } catch (error: Exception) {
                    conn.rollback()
                    throw error
                } finally {
                    conn.autoCommit = true
                }
            }
        }

    suspend fun getTextTurnState(interactionId: String): TextTurnCheckpoint? = withContext(Dispatchers.IO) {
        openConnection().use { conn ->
            conn.prepareStatement("""
                SELECT interaction_id, state, conversation_id, failure_type
                FROM turn_checkpoints WHERE interaction_id=?
            """.trimIndent()).use { statement ->
                statement.setString(1, interactionId)
                statement.executeQuery().use { results ->
                    if (results.next()) TextTurnCheckpoint(
                        results.getString("interaction_id"),
                        results.getString("state"),
                        results.getString("conversation_id"),
                        results.getString("failure_type"),
                    ) else null
                }
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
                val tokens = Regex("[\\p{L}\\p{N}_]+").findAll(query)
                    .map { it.value }.take(20).toList()
                require(tokens.isNotEmpty()) { "Search must include a word or number" }
                stmt.setString(1, tokens.joinToString(" AND ") { token ->
                    "\"" + token.replace("\"", "\"\"") + "\""
                })
                stmt.setInt(2, limit.coerceIn(1, 100))
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

    suspend fun deleteMemory(id: String): Boolean = withContext(Dispatchers.IO) {
        require(id.isNotBlank() && id.length <= 256) { "Invalid memory ID" }
        openConnection().use { conn ->
            conn.prepareStatement("DELETE FROM memories WHERE id = ?").use { stmt ->
                stmt.setString(1, id)
                stmt.executeUpdate() == 1
            }
        }
    }

    suspend fun storeMemory(memory: MemoryRecord) = withContext(Dispatchers.IO) {
        openConnection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO memories (id, content, source, scope, confidence, expires_at, created_at, updated_at, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    content=excluded.content, source=excluded.source, scope=excluded.scope,
                    confidence=excluded.confidence, expires_at=excluded.expires_at,
                    updated_at=excluded.updated_at, metadata=excluded.metadata
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
            var publicKey = map["public_key"].orEmpty()
            var privateKey = map["private_key"].orEmpty()
            if (publicKey.isBlank() || privateKey.isBlank()) {
                val pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
                publicKey = java.util.Base64.getEncoder().encodeToString(pair.public.encoded)
                privateKey = java.util.Base64.getEncoder().encodeToString(pair.private.encoded)
                conn.autoCommit = false
                try {
                    conn.prepareStatement("INSERT OR REPLACE INTO node_identity (key,value,updated_at) VALUES (?,?,?)").use { stmt ->
                        for ((key, value) in mapOf("public_key" to publicKey, "private_key" to privateKey)) {
                            stmt.setString(1, key)
                            stmt.setString(2, value)
                            stmt.setString(3, Instant.now().toString())
                            stmt.addBatch()
                        }
                        stmt.executeBatch()
                    }
                    conn.commit()
                } catch (e: Exception) {
                    conn.rollback()
                    throw e
                } finally {
                    conn.autoCommit = true
                }
            }
            
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


    /**
     * Lightweight SQLite integrity check for diagnostics and pre-deployment acceptance.
     * Never dumps private memory or conversation bodies into diagnostic output.
     */
    /** Typed, content-free durable checkpoints. Never store prompts or model tokens here. */
    suspend fun beginTurn(interactionId: String) = withContext(Dispatchers.IO) {
        require(interactionId.isNotBlank() && interactionId.length <= 128)
        val now = Instant.now().toString()
        openConnection().use { conn ->
            conn.prepareStatement("INSERT INTO turn_checkpoints (interaction_id,state,created_at,updated_at) VALUES (?,'ACCEPTED',?,?)").use { stmt ->
                stmt.setString(1, interactionId)
                stmt.setString(2, now)
                stmt.setString(3, now)
                check(stmt.executeUpdate() == 1)
            }
        }
    }

    suspend fun markTurn(interactionId: String, state: String, conversationId: String? = null, failureType: String? = null) =
        withContext(Dispatchers.IO) {
            require(state in setOf("REASONING", "COMMITTED", "FAILED")) { "Invalid checkpoint transition" }
            require(failureType == null || failureType.matches(Regex("[A-Z_]{1,64}"))) { "Invalid failure type" }
            val allowedPrevious = if (state == "FAILED") setOf("ACCEPTED", "REASONING")
                else if (state == "REASONING") setOf("ACCEPTED") else setOf("REASONING")
            openConnection().use { conn ->
                val placeholders = allowedPrevious.joinToString(",") { "'$it'" }
                conn.prepareStatement("""
                    UPDATE turn_checkpoints
                    SET state=?, updated_at=?, conversation_id=?, failure_type=?
                    WHERE interaction_id=? AND state IN ($placeholders)
                """.trimIndent()).use { stmt ->
                    stmt.setString(1, state)
                    stmt.setString(2, Instant.now().toString())
                    stmt.setString(3, conversationId)
                    stmt.setString(4, failureType)
                    stmt.setString(5, interactionId)
                    check(stmt.executeUpdate() == 1) { "Invalid or duplicate turn transition" }
                }
            }
        }

    /**
     * A crash never causes automatic generation or tool replay. Old inflight turns
     * are marked INTERRUPTED and remain visible for diagnostics.
     */
    suspend fun reconcileInterruptedTurns(): Int = withContext(Dispatchers.IO) {
        openConnection().use { conn ->
            conn.prepareStatement("""
                UPDATE turn_checkpoints SET state='INTERRUPTED', updated_at=?
                WHERE state IN ('ACCEPTED','REASONING')
            """.trimIndent()).use { stmt ->
                stmt.setString(1, Instant.now().toString())
                stmt.executeUpdate()
            }
        }
    }

    suspend fun countInterruptedTurns(): Long = withContext(Dispatchers.IO) {
        openConnection().use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM turn_checkpoints WHERE state='INTERRUPTED'").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
        }
    }

    suspend fun checkDatabase(): StorageHealth = withContext(Dispatchers.IO) {
        openConnection().use { conn ->
            val result = conn.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA quick_check(1)").use { rs ->
                    if (rs.next()) rs.getString(1) else "no result"
                }
            }
            val version = conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version").use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
            val memoryCount = conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM memories").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
            val conversationCount = conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM conversations").use { rs ->
                    if (rs.next()) rs.getLong(1) else 0L
                }
            }
            StorageHealth(
                integrityOk = result == "ok", integrityMessage = result.take(200),
                schemaVersion = version, memoryCount = memoryCount,
                conversationCount = conversationCount,
                databaseBytes = Files.size(dbPath)
            )
        }
    }

    /**
     * SQLite VACUUM INTO produces a consistent point-in-time database snapshot,
     * including the node's sensitive identity material. The destination must be
     * an absolute, previously nonexistent, non-symlink file. Never overwrite.
     */
    suspend fun createBackup(destination: Path): StorageBackupReceipt = withContext(Dispatchers.IO) {
        require(destination.isAbsolute) { "Backup destination must be absolute" }
        val path = destination.normalize()
        require(path != dbPath.toAbsolutePath().normalize()) { "Cannot overwrite active database" }
        require(!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            "Backup destination already exists"
        }
        val parent = path.parent ?: throw IllegalArgumentException("Missing backup parent")
        require(Files.isDirectory(parent) && !Files.isSymbolicLink(parent)) {
            "Backup parent must be an existing non-symlink directory"
        }
        val perms = java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
            setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)
        )
        Files.createFile(path, perms)
        try {
            openConnection().use { conn ->
                val quotedPath = path.toString().replace("'", "''")
                conn.createStatement().use { stmt ->
                    stmt.execute("VACUUM INTO '$quotedPath'")
                }
            }
            val copy = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("PRAGMA quick_check(1)").use { rs ->
                        rs.next() && rs.getString(1) == "ok"
                    }
                }
            }
            check(copy) { "Backup failed integrity validation" }
            Files.setPosixFilePermissions(path, setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
            ))
            StorageBackupReceipt(path.toString(), Files.size(path))
        } catch (error: Exception) {
            runCatching { Files.deleteIfExists(path) }
            throw error
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
/** Content-free diagnostics; never include node private keys or stored text. */
data class StorageHealth(
    val integrityOk: Boolean,
    val integrityMessage: String,
    val schemaVersion: Int,
    val memoryCount: Long,
    val conversationCount: Long,
    val databaseBytes: Long,
)

data class StorageBackupReceipt(val path: String, val bytes: Long)

/** Content-free checkpoint lookup for diagnostics and contract tests. */
data class TextTurnCheckpoint(
    val interactionId: String,
    val state: String,
    val conversationId: String?,
    val failureType: String?,
)
