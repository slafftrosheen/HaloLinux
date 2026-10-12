package com.halo.linux.agent

import com.halo.runtime.cognition.agent.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Native JVM implementation of the actual canonical Halo AgentRunStore.
 *
 * v1 scope: text-only journal, no tool claims/dispatch and no automatic replay.
 * Operations requiring two mutations are one SQLite transaction. Claims are
 * conditional updates; deduplication is guaranteed by DB constraints.
 *
 * One daemon owns this database. StateFlow notifications cover THIS store
 * instance, not independent processes. Linux's daemon instance lock is
 * mandatory before initializing/reconciling the store.
 */
class SqliteAgentRunStore(
    private val database: Path,
    private val now: () -> Instant = Instant::now,
) : AgentRunStore, AutoCloseable {
    private val monitor = Any()
    private val epoch = MutableStateFlow(0L)
    @Volatile private var closed = false

    init {
        require(database.isAbsolute) { "Agent run database path must be absolute" }
        require(!Files.isSymbolicLink(database)) { "Refusing a symlinked agent database" }
        val parent = database.parent ?: error("Database has no parent")
        Files.createDirectories(parent)
        require(!Files.isSymbolicLink(parent)) { "Refusing symlinked agent database directory" }
        Files.setPosixFilePermissions(parent, setOf(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE
        ))
        synchronized(monitor) {
            connection().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("PRAGMA journal_mode=WAL")
                    stmt.execute("CREATE TABLE IF NOT EXISTS agent_schema (version INTEGER NOT NULL)")
                    val version = stmt.executeQuery("SELECT version FROM agent_schema LIMIT 1").use {
                        if (it.next()) it.getInt(1) else null
                    }
                    require(version == null || version == 1) {
                        "Unknown canonical agent journal database schema: $version"
                    }
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS agent_runs (
                          id TEXT PRIMARY KEY, interaction_id TEXT NOT NULL,
                          parent_run_id TEXT REFERENCES agent_runs(id),
                          root_run_id TEXT, depth INTEGER NOT NULL,
                          state TEXT NOT NULL, updated_at TEXT NOT NULL,
                          payload_json TEXT NOT NULL
                        )
                    """.trimIndent())
                    stmt.execute("CREATE INDEX IF NOT EXISTS agent_runs_state_idx ON agent_runs(state)")
                    stmt.execute("CREATE INDEX IF NOT EXISTS agent_runs_parent_idx ON agent_runs(parent_run_id)")
                    stmt.execute("""
                        CREATE TABLE IF NOT EXISTS agent_steps (
                          id TEXT PRIMARY KEY,
                          run_id TEXT NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
                          ordinal INTEGER NOT NULL,
                          type TEXT NOT NULL,
                          schema_version INTEGER NOT NULL,
                          payload_json TEXT NOT NULL,
                          input_request_id TEXT,
                          resolution_request_id TEXT,
                          recovery_fingerprint TEXT,
                          journal_key TEXT,
                          UNIQUE(run_id, ordinal)
                        )
                    """.trimIndent())
                    stmt.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS agent_input_requested_unique
                        ON agent_steps(input_request_id) WHERE input_request_id IS NOT NULL
                    """.trimIndent())
                    stmt.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS agent_input_resolution_unique
                        ON agent_steps(resolution_request_id) WHERE resolution_request_id IS NOT NULL
                    """.trimIndent())
                    stmt.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS agent_journal_identity_unique
                        ON agent_steps(run_id, journal_key)
                        WHERE journal_key IS NOT NULL
                    """.trimIndent())
                    // A repeated dispatch/claim idempotency key must never
                    // be admitted as a second external execution claim.
                    stmt.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS agent_execution_claim_key_unique
                        ON agent_steps(json_extract(payload_json,'$.idempotencyKey'))
                        WHERE type IN ('ToolDispatched','ToolExecutionClaimed')
                          AND json_extract(payload_json,'$.idempotencyKey') IS NOT NULL
                    """.trimIndent())
                    stmt.execute("""
                        CREATE UNIQUE INDEX IF NOT EXISTS agent_recovery_claim_unique
                        ON agent_steps(run_id, recovery_fingerprint)
                        WHERE recovery_fingerprint IS NOT NULL
                    """.trimIndent())
                    if (version == null) stmt.execute("INSERT INTO agent_schema(version) VALUES(1)")
                }
            }
        }
        Files.setPosixFilePermissions(database, setOf(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE
        ))
    }

    private fun connection(): Connection {
        check(!closed) { "AgentRunStore already closed" }
        return DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").also { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA foreign_keys=ON")
                stmt.execute("PRAGMA busy_timeout=5000")
            }
        }
    }

    private fun <T> read(block: (Connection) -> T): T = synchronized(monitor) {
        connection().use(block)
    }

    private fun <T> transaction(block: (Connection) -> T): T = synchronized(monitor) {
        connection().use { conn ->
            conn.autoCommit = false
            try {
                val value = block(conn)
                conn.commit()
                epoch.value += 1
                value
            } catch (failure: Exception) {
                conn.rollback()
                throw failure
            } finally {
                conn.autoCommit = true
            }
        }
    }

    private fun insertRun(conn: Connection, run: AgentRun): Boolean {
        require(run.steps.isEmpty()) { "Create a run before appending its journal steps" }
        require(run.delegationDepth >= 0)
        val payload = CanonicalAgentJson.encodeRun(run) // fail before SQL mutation
        return conn.prepareStatement("""
            INSERT OR IGNORE INTO agent_runs(
              id,interaction_id,parent_run_id,root_run_id,depth,state,updated_at,payload_json
            ) VALUES(?,?,?,?,?,?,?,?)
        """.trimIndent()).use { stmt ->
            stmt.setString(1, run.id.value)
            stmt.setString(2, run.interactionId.value)
            stmt.setString(3, run.parentRunId?.value)
            stmt.setString(4, run.rootRunId?.value)
            stmt.setInt(5, run.delegationDepth)
            stmt.setString(6, run.state.name)
            stmt.setString(7, run.updatedAt.toString())
            stmt.setString(8, payload)
            stmt.executeUpdate() == 1
        }
    }

    private fun stepRows(conn: Connection, runId: String): List<AgentStep> =
        conn.prepareStatement("""
            SELECT payload_json,schema_version FROM agent_steps
            WHERE run_id=? ORDER BY ordinal ASC
        """.trimIndent()).use { stmt ->
            stmt.setString(1, runId)
            stmt.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        require(rows.getInt("schema_version") == CanonicalAgentJson.VERSION) {
                            "Unrecognized persisted agent step schema"
                        }
                        add(CanonicalAgentJson.decodeStep(rows.getString("payload_json")))
                    }
                }
            }
        }

    private fun getIn(conn: Connection, id: String): AgentRun? =
        conn.prepareStatement("""
            SELECT payload_json,state,updated_at FROM agent_runs WHERE id=?
        """.trimIndent()).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) null else CanonicalAgentJson.decodeRun(
                    rs.getString("payload_json"), stepRows(conn, id)
                ).copy(
                    state = AgentRunState.valueOf(rs.getString("state")),
                    updatedAt = Instant.parse(rs.getString("updated_at"))
                )
            }
        }

    private fun allIn(conn: Connection, predicate: String = ""): List<AgentRun> =
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT id FROM agent_runs " + predicate + " ORDER BY updated_at DESC,id ASC"
            ).use { rs ->
                val ids = buildList {
                    while (rs.next()) add(rs.getString(1))
                }
                ids.mapNotNull { getIn(conn, it) }
            }
        }

    override fun observeRuns(): Flow<List<AgentRun>> =
        epoch.map { withContext(Dispatchers.IO) { read { allIn(it) } } }

    override suspend fun get(runId: AgentRunId): AgentRun? =
        withContext(Dispatchers.IO) { read { getIn(it, runId.value) } }

    override suspend fun create(run: AgentRun) = withContext(Dispatchers.IO) {
        transaction { conn ->
            check(insertRun(conn, run)) { "Agent run ID already exists" }
        }
    }

    override suspend fun update(run: AgentRun) = withContext(Dispatchers.IO) {
        transaction { conn ->
            val current = getIn(conn, run.id.value)
                ?: throw IllegalArgumentException("Unknown agent run")
            check(!current.state.isTerminal) { "Terminal run is immutable" }
            check(run.updatedAt >= current.updatedAt) { "Stale agent run update" }
            check(current.steps.map { it.id } == run.steps.map { it.id }) {
                "Run updates may not rewrite or discard durable journal steps"
            }
            check(current.parentRunId == run.parentRunId &&
                current.rootRunId == run.rootRunId &&
                current.delegationDepth == run.delegationDepth) {
                "Run ancestry and delegation depth are immutable"
            }
            val payload = CanonicalAgentJson.encodeRun(run)
            val count = conn.prepareStatement("""
                UPDATE agent_runs SET state=?,updated_at=?,payload_json=?
                WHERE id=? AND state=? AND updated_at=?
            """.trimIndent()).use { stmt ->
                stmt.setString(1, run.state.name)
                stmt.setString(2, run.updatedAt.toString())
                stmt.setString(3, payload)
                stmt.setString(4, run.id.value)
                stmt.setString(5, current.state.name)
                stmt.setString(6, current.updatedAt.toString())
                stmt.executeUpdate()
            }
            check(count == 1) { "Concurrent run update lost its CAS precondition" }
        }
    }

    private fun appendIn(conn: Connection, step: AgentStep): Boolean {
        val payload = CanonicalAgentJson.encodeStep(step)
        val kind = step.javaClass.simpleName
        if (step is AgentStep.InputRequested) {
            require(step.input.id == step.requestId && step.input.runId == step.runId) {
                "Pending input metadata mismatches journal identity"
            }
        }
        val requested = (step as? AgentStep.InputRequested)?.requestId?.value
        val resolved = (step as? AgentStep.InputResolved)?.requestId?.value
        val fingerprint = (step as? AgentStep.RecoveryClaimed)?.let {
            org.json.JSONArray(listOf(it.planKind, it.evidenceStepId)).toString()
        }
        val journalKey = when (step) {
            is AgentStep.RunPrepared -> "run_prepared"
            is AgentStep.ProviderTurnStarted -> "provider_start:${step.turnId.value}"
            is AgentStep.ProviderTurnCompleted -> "provider_finish:${step.turnId.value}"
            is AgentStep.ResponseProduced -> "response:${step.responseId}"
            is AgentStep.ResponseCommitted -> "commit:${step.commitId}"
            else -> null
        }
        // Journal evidence is permitted. This store never dispatches tools; a
        // claimed execution is not proof of an external action's outcome.
        val ordinal = conn.prepareStatement("""
            SELECT COALESCE(MAX(ordinal),0)+1 FROM agent_steps WHERE run_id=?
        """.trimIndent()).use { stmt ->
            stmt.setString(1, step.runId.value)
            stmt.executeQuery().use { result -> result.next(); result.getInt(1) }
        }
        return conn.prepareStatement("""
            INSERT OR IGNORE INTO agent_steps(
              id,run_id,ordinal,type,schema_version,payload_json,
              input_request_id,resolution_request_id,recovery_fingerprint,journal_key
            ) VALUES(?,?,?,?,?,?,?,?,?,?)
        """.trimIndent()).use { stmt ->
            stmt.setString(1, step.id.value)
            stmt.setString(2, step.runId.value)
            stmt.setInt(3, ordinal)
            stmt.setString(4, kind)
            stmt.setInt(5, CanonicalAgentJson.VERSION)
            stmt.setString(6, payload)
            stmt.setString(7, requested)
            stmt.setString(8, resolved)
            stmt.setString(9, fingerprint)
            stmt.setString(10, journalKey)
            stmt.executeUpdate() == 1
        }
    }

    override suspend fun appendStep(step: AgentStep): Boolean = withContext(Dispatchers.IO) {
        transaction { conn ->
            val run = getIn(conn, step.runId.value) ?: return@transaction false
            if (run.state.isTerminal) return@transaction false
            if (step is AgentStep.InputResolved) return@transaction false // use atomic resolution
            appendIn(conn, step)
        }
    }

    override suspend fun children(parent: AgentRunId): List<AgentRun> =
        withContext(Dispatchers.IO) { read { conn ->
            conn.prepareStatement("""
                SELECT id FROM agent_runs WHERE parent_run_id=? ORDER BY updated_at,id
            """.trimIndent()).use { stmt ->
                stmt.setString(1, parent.value)
                stmt.executeQuery().use { rs ->
                    val ids = buildList { while (rs.next()) add(rs.getString(1)) }
                    ids.mapNotNull { getIn(conn, it) }
                }
            }
        } }

    override suspend fun createChildIfAllowed(
        parent: AgentRunId, child: AgentRun, maxChildren: Int,
    ): Boolean = withContext(Dispatchers.IO) {
        transaction { conn ->
            if (maxChildren <= 0 || child.parentRunId != parent) return@transaction false
            val parentRun = getIn(conn, parent.value) ?: return@transaction false
            if (parentRun.state.isTerminal ||
                child.delegationDepth != parentRun.delegationDepth + 1 ||
                child.rootRunId?.value != (parentRun.rootRunId?.value ?: parent.value)
            ) return@transaction false
            val number = conn.prepareStatement(
                "SELECT COUNT(*) FROM agent_runs WHERE parent_run_id=?"
            ).use { stmt ->
                stmt.setString(1, parent.value)
                stmt.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
            number < maxChildren && insertRun(conn, child)
        }
    }

    override suspend fun nonTerminalRuns(): List<AgentRun> =
        withContext(Dispatchers.IO) { read { conn ->
            allIn(conn, """
                WHERE state NOT IN ('COMPLETED','FAILED','CANCELLED','INTERRUPTED')
            """.trimIndent())
        } }

    override suspend fun claimResume(
        runId: AgentRunId, expectedState: AgentRunState,
    ): Boolean = withContext(Dispatchers.IO) {
        if (expectedState.isTerminal || expectedState == AgentRunState.EXECUTING) return@withContext false
        transaction { conn ->
            conn.prepareStatement("""
                UPDATE agent_runs SET state='EXECUTING', updated_at=?
                WHERE id=? AND state=?
            """.trimIndent()).use { stmt ->
                stmt.setString(1, now().toString())
                stmt.setString(2, runId.value)
                stmt.setString(3, expectedState.name)
                stmt.executeUpdate() == 1
            }
        }
    }

    override suspend fun claimRecovery(
        runId: AgentRunId, expectedState: AgentRunState,
        claim: AgentStep.RecoveryClaimed,
    ): Boolean = withContext(Dispatchers.IO) {
        if (claim.runId != runId || expectedState.isTerminal ||
            expectedState == AgentRunState.EXECUTING) return@withContext false
        try {
            transaction { conn ->
            val current = getIn(conn, runId.value) ?: return@transaction false
            if (current.state != expectedState) return@transaction false
            val fingerprint = org.json.JSONArray(listOf(claim.planKind, claim.evidenceStepId)).toString()
            val duplicate = conn.prepareStatement("""
                SELECT 1 FROM agent_steps WHERE run_id=? AND recovery_fingerprint=?
            """.trimIndent()).use { stmt ->
                stmt.setString(1, runId.value)
                stmt.setString(2, fingerprint)
                stmt.executeQuery().use { it.next() }
            }
            if (duplicate) return@transaction false
            // Decode/validate the step before claiming the run.
            CanonicalAgentJson.encodeStep(claim)
            val updated = conn.prepareStatement("""
                UPDATE agent_runs SET state='EXECUTING',updated_at=?
                WHERE id=? AND state=?
            """.trimIndent()).use { stmt ->
                stmt.setString(1, now().toString())
                stmt.setString(2, runId.value)
                stmt.setString(3, expectedState.name)
                stmt.executeUpdate()
            }
            if (updated != 1) return@transaction false
            if (!appendIn(conn, claim)) throw DuplicateRecoveryClaim()
            true
            }
        } catch (_: DuplicateRecoveryClaim) {
            false
        }
    }

    override suspend fun resolveInputIfPending(
        requestId: AgentInputRequestId, resolution: AgentInputResolution,
    ): Boolean = withContext(Dispatchers.IO) {
        transaction { conn ->
            val requestRow = conn.prepareStatement("""
                SELECT run_id,payload_json FROM agent_steps
                WHERE input_request_id=? AND type='InputRequested'
            """.trimIndent()).use { stmt ->
                stmt.setString(1, requestId.value)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) null else rs.getString(1) to rs.getString(2)
                }
            } ?: return@transaction false
            val run = getIn(conn, requestRow.first) ?: return@transaction false
            if (run.state.isTerminal) return@transaction false
            val journalStep = CanonicalAgentJson.decodeStep(requestRow.second) as AgentStep.InputRequested
            val input = journalStep.input
            val inCorrectState = when (input) {
                is AgentInputRequest.Approval -> run.state == AgentRunState.WAITING_FOR_APPROVAL
                is AgentInputRequest.Clarification -> run.state == AgentRunState.WAITING_FOR_CLARIFICATION
            }
            if (!inCorrectState) return@transaction false
            val valid = when (input) {
                is AgentInputRequest.Approval -> resolution is AgentInputResolution.Approved ||
                    resolution is AgentInputResolution.Denied ||
                    resolution is AgentInputResolution.Cancelled ||
                    resolution is AgentInputResolution.Expired
                is AgentInputRequest.Clarification -> resolution is AgentInputResolution.Clarified ||
                    resolution is AgentInputResolution.Cancelled ||
                    resolution is AgentInputResolution.Expired
            }
            if (!valid) return@transaction false
            if (input is AgentInputRequest.Clarification &&
                resolution is AgentInputResolution.Clarified) {
                val expected = input.expected as? ExpectedInput.Text
                    ?: return@transaction false
                val value = resolution.value
                if (value.length !in expected.minLength..expected.maxLength ||
                    (expected.singleLine && (value.contains('\\n') || value.contains('\\r')))) {
                    return@transaction false
                }
            }
            if (input.expiresAt != null && !now().isBefore(input.expiresAt) &&
                resolution !is AgentInputResolution.Expired) return@transaction false
            val step = AgentStep.InputResolved(
                id = AgentStepId(UUID.randomUUID().toString()),
                runId = run.id, createdAt = now(),
                requestId = requestId,
                resolution = AgentInputResolutions.encode(resolution),
            )
            appendIn(conn, step)
        }
    }

    /**
     * Consistent online SQLite snapshot. Backup contains private request text,
     * journal evidence, and potentially authorization metadata: protect it.
     * Never copy the raw .db file while WAL is active.
     */
    suspend fun backupTo(destination: Path): Long = withContext(Dispatchers.IO) {
        require(destination.isAbsolute) { "Absolute backup path required" }
        val path = destination.normalize()
        require(path != database.toAbsolutePath().normalize()) { "Refusing to overwrite live journal" }
        require(!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            "Backup destination must not exist"
        }
        val parent = path.parent ?: throw IllegalArgumentException("Backup parent missing")
        require(Files.isDirectory(parent) && !Files.isSymbolicLink(parent)) {
            "Backup directory must exist and must not be a symlink"
        }
        val perms = Files.getPosixFilePermissions(parent)
        require(perms.none {
            it == PosixFilePermission.GROUP_WRITE ||
                it == PosixFilePermission.OTHERS_WRITE ||
                it == PosixFilePermission.GROUP_READ ||
                it == PosixFilePermission.OTHERS_READ ||
                it == PosixFilePermission.GROUP_EXECUTE ||
                it == PosixFilePermission.OTHERS_EXECUTE
        }) {
            "Backup directory must be private (0700)."
        }
        synchronized(monitor) {
            val attr = java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
            Files.createFile(path, attr)
            try {
                connection().use { conn ->
                    conn.prepareStatement("VACUUM INTO ?").use { stmt ->
                        stmt.setString(1, path.toString())
                        stmt.execute()
                    }
                }
                DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { verify ->
                    verify.createStatement().use { stmt ->
                        stmt.executeQuery("PRAGMA quick_check").use { results ->
                            check(results.next() && results.getString(1) == "ok") {
                                "Agent journal backup integrity check failed"
                            }
                        }
                    }
                }
                Files.setPosixFilePermissions(path, setOf(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE
                ))
                Files.size(path)
            } catch (error: Exception) {
                runCatching { Files.deleteIfExists(path) }
                throw error
            }
        }
    }

    override fun close() {
        synchronized(monitor) { closed = true }
    }

    class DuplicateRecoveryClaim : IllegalStateException("Recovery fingerprint already claimed")
}
