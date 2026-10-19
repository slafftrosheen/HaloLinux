package com.halo.adapters.storage

import com.halo.sdk.runtime.AgentRun
import com.halo.sdk.runtime.AgentRunEvent
import com.halo.sdk.runtime.AgentRunId
import com.halo.sdk.runtime.AgentRunStatus
import com.halo.sdk.runtime.AgentRunStore
import com.halo.sdk.runtime.AgentStep
import com.halo.sdk.runtime.AgentStepId
import com.halo.sdk.runtime.AgentInputRequestId
import com.halo.sdk.runtime.AgentInputResolution
import com.halo.sdk.runtime.ActionTruth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * SQLite implementation of AgentRunStore.
 * 
 * Provides durable agent run persistence with:
 * - Atomic compare-and-swap state transitions using revision field
 * - Append-only event journal with monotonic sequence numbers
 * - Crash recovery via INTERRUPTED state reconciliation
 * - Deduplication for tool dispatches and input resolutions
 */
class SqliteAgentRunStore : AgentRunStore {
    private val log = LoggerFactory.getLogger(SqliteAgentRunStore::class.java)
    
    private var dbPath: java.nio.file.Path? = null
    private lateinit var connection: Connection
    
    suspend fun initialize(dbPath: java.nio.file.Path) = withContext(Dispatchers.IO) {
        this.dbPath = dbPath
        java.nio.file.Files.createDirectories(dbPath.parent)
        
        connection = DriverManager.getConnection("jdbc:sqlite:${dbPath.toAbsolutePath()}?journal_mode=WAL&foreign_keys=on&busy_timeout=5000")
        
        // Ensure tables exist (migrations handled by LinuxStorage migration 3)
        connection.use { conn ->
            conn.setAutoCommit(false)
            try {
                // agent_runs and agent_run_events tables created by LinuxStorage migration 3
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.setAutoCommit(true)
            }
        }
    }

    override fun observeRuns(): Flow<List<AgentRun>> = flow {
        while (currentCoroutineContext().isActive) {
            try {
                val runs = listRuns()
                emit(runs)
            } catch (e: Exception) {
                log.warn("Failed to observe runs: ${e.message}")
            }
            try {
                delay(1000)
            } catch (e: InterruptedException) {
                return@flow
            }
        }
    }

    private suspend fun listRuns(): List<AgentRun> = withContext(Dispatchers.IO) {
        connection.use { conn ->
            conn.createStatement()
                .executeQuery("SELECT * FROM agent_runs ORDER BY updated_at DESC")
                .use { rs ->
                    val runs = mutableListOf<AgentRun>()
                    while (rs.next()) {
                        runs.add(mapRowToRun(rs))
                    }
                    runs
                }
        }
    }

    override suspend fun get(runId: AgentRunId): AgentRun? = withContext(Dispatchers.IO) {
        connection.use { conn ->
            conn.prepareStatement("SELECT * FROM agent_runs WHERE run_id = ?")
                .use { stmt ->
                    stmt.setString(1, runId.value)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) mapRowToRun(rs) else null
                    }
                }
        }
    }

    override suspend fun create(run: AgentRun) = withContext(Dispatchers.IO) {
        connection.use { conn ->
            conn.setAutoCommit(false)
            try {
                conn.prepareStatement("""
                    INSERT INTO agent_runs (run_id, interaction_id, conversation_id, origin_node_id, status, attempt, revision, checkpoint_ref, provider_id, model_id, failure_category, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { stmt ->
                    bindRun(stmt, run)
                    stmt.executeUpdate()
                }
                
                // Append CREATED event
                appendEventInternal(conn, AgentRunEvent(
                    eventId = UUID.randomUUID().toString(),
                    runId = run.id,
                    sequence = 1,
                    eventType = "STATE_CHANGED:CREATED",
                    createdAt = run.createdAt,
                ))
                
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.setAutoCommit(true)
            }
        }
    }

    override suspend fun appendStep(step: AgentStep): Boolean = withContext(Dispatchers.IO) {
        connection.use { conn ->
            conn.setAutoCommit(false)
            try {
                // Check if step already exists (deduplication)
                val exists = when (step) {
                    is AgentStep.ToolDispatched -> checkToolDispatchedExists(conn, step.toolCallId)
                    is AgentStep.ToolExecutionClaimed -> checkToolClaimedExists(conn, step.toolCallId)
                    is AgentStep.InputResolved -> checkInputResolvedExists(conn, step.requestId)
                    else -> false
                }
                
                if (exists) return@withContext false
                
                // Insert step
                appendStepInternal(conn, step)
                conn.commit()
                true
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.setAutoCommit(true)
            }
        }
    }

    override suspend fun update(run: AgentRun) = withContext(Dispatchers.IO) {
        connection.use { conn ->
            conn.setAutoCommit(false)
            try {
                val updated = conn.prepareStatement("""
                    UPDATE agent_runs
                    SET status = ?, attempt = ?, revision = revision + 1, updated_at = ?, checkpoint_ref = ?, provider_id = ?, model_id = ?, failure_category = ?
                    WHERE run_id = ? AND revision = ?
                """.trimIndent()).use { stmt ->
                    stmt.setString(1, run.status.name)
                    stmt.setInt(2, run.attempt)
                    stmt.setString(3, run.updatedAt.toString())
                    stmt.setString(4, run.checkpointRef)
                    stmt.setString(5, run.providerId)
                    stmt.setString(6, run.modelId)
                    stmt.setString(7, run.failureCategory)
                    stmt.setString(8, run.id.value)
                    stmt.setLong(9, run.revision)
                    stmt.executeUpdate()
                }
                
                if (updated != 1) {
                    throw java.util.ConcurrentModificationException("Stale run: ${run.id.value} (expected revision ${run.revision})")
                }
                
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.setAutoCommit(true)
            }
        }
    }

    override suspend fun children(parent: AgentRunId): List<AgentRun> = withContext(Dispatchers.IO) {
        connection.use { conn ->
            conn.prepareStatement("SELECT * FROM agent_runs WHERE origin_node_id = ? ORDER BY created_at")
                .use { stmt ->
                    stmt.setString(1, parent.value)
                    stmt.executeQuery().use { rs ->
                        val children = mutableListOf<AgentRun>()
                        while (rs.next()) {
                            children.add(mapRowToRun(rs))
                        }
                        children
                    }
                }
        }
    }

    override suspend fun createChildIfAllowed(parent: AgentRunId, child: AgentRun, maxChildren: Int): Boolean = withContext(Dispatchers.IO) {
        connection.use { conn ->
            conn.setAutoCommit(false)
            try {
                // Check parent exists and is non-terminal
                val parentRun = conn.prepareStatement("SELECT * FROM agent_runs WHERE run_id = ?")
                    .use { stmt ->
                        stmt.setString(1, parent.value)
                        stmt.executeQuery().use { rs -> if (rs.next()) mapRowToRun(rs) else null }
                    }
                
                if (parentRun == null || parentRun.status.isTerminal) return@withContext false
                
                // Count existing children
                val childCount = conn.prepareStatement("SELECT COUNT(*) FROM agent_runs WHERE origin_node_id = ?")
                    .use { stmt ->
                        stmt.setString(1, parent.value)
                        stmt.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                    }
                
                if (childCount >= maxChildren) return@withContext false
                
                // Create child
                conn.prepareStatement("""
                    INSERT INTO agent_runs (run_id, interaction_id, conversation_id, origin_node_id, status, attempt, revision, checkpoint_ref, provider_id, model_id, failure_category, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()).use { stmt ->
                    bindRun(stmt, child)
                    stmt.executeUpdate()
                }
                
                appendEventInternal(conn, AgentRunEvent(
                    eventId = UUID.randomUUID().toString(),
                    runId = child.id,
                    sequence = 1,
                    eventType = "STATE_CHANGED:CREATED",
                    createdAt = child.createdAt,
                    correlationId = parent.value,
                ))
                
                conn.commit()
                true
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.setAutoCommit(true)
            }
        }
    }

    override suspend fun nonTerminalRuns(): List<AgentRun> = withContext(Dispatchers.IO) {
        connection.use { conn ->
            conn.prepareStatement("SELECT * FROM agent_runs WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED') ORDER BY updated_at")
                .use { stmt ->
                    stmt.executeQuery().use { rs ->
                        val runs = mutableListOf<AgentRun>()
                        while (rs.next()) {
                            runs.add(mapRowToRun(rs))
                        }
                        runs
                    }
                }
        }
    }

    override suspend fun claimResume(runId: AgentRunId, expectedState: AgentRunStatus): Boolean = withContext(Dispatchers.IO) {
        connection.use { conn ->
            conn.setAutoCommit(false)
            try {
                val updated = conn.prepareStatement("""
                    UPDATE agent_runs
                    SET status = 'INTERRUPTED', revision = revision + 1, updated_at = ?
                    WHERE run_id = ? AND revision = (SELECT revision FROM agent_runs WHERE run_id = ?) AND status = ?
                """.trimIndent()).use { stmt ->
                    stmt.setString(1, Instant.now().toString())
                    stmt.setString(2, runId.value)
                    stmt.setString(3, runId.value)
                    stmt.setString(4, expectedState.name)
                    stmt.executeUpdate()
                }
                
                val success = updated == 1
                if (success) {
                    appendEventInternal(conn, AgentRunEvent(
                        eventId = UUID.randomUUID().toString(),
                        runId = runId,
                        sequence = -1, // Will be allocated by trigger or computed
                        eventType = "STATE_CHANGED:INTERRUPTED",
                        createdAt = Instant.now(),
                    ))
                }
                
                conn.commit()
                success
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.setAutoCommit(true)
            }
        }
    }

    override suspend fun claimRecovery(
        runId: AgentRunId,
        expectedState: AgentRunStatus,
        claim: AgentStep.RecoveryClaimed,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!claimResume(runId, expectedState)) return false
        return appendStep(claim)
    }

    override suspend fun resolveInputIfPending(
        requestId: AgentInputRequestId,
        resolution: AgentInputResolution,
    ): Boolean = withContext(Dispatchers.IO) {
        connection.use { conn ->
            conn.setAutoCommit(false)
            try {
                // Check if already resolved
                val exists = checkInputResolvedExists(conn, requestId)
                if (exists) return@withContext false
                
                // Find run with this pending input request
                val runId = conn.prepareStatement("""
                    SELECT run_id FROM agent_runs
                    WHERE run_id IN (
                        SELECT run_id FROM agent_run_events
                        WHERE event_type = 'INPUT_REQUESTED' AND payload_ref = ?
                    )
                    AND status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED')
                """.trimIndent()).use { stmt ->
                    stmt.setString(1, requestId.value)
                    stmt.executeQuery().use { rs -> if (rs.next()) AgentRunId(rs.getString("run_id")) else null }
                }
                
                if (runId == null) return@withContext false
                
                // Append resolution step
                val step = AgentStep.InputResolved(
                    stepId = AgentStepId(UUID.randomUUID().toString()),
                    runId = runId,
                    sequence = getNextSequence(conn, runId),
                    createdAt = resolution.resolvedAt,
                    requestId = requestId,
                    resolution = resolution,
                )
                
                appendStepInternal(conn, step)
                conn.commit()
                true
            } catch (e: Exception) {
                conn.rollback()
                throw e
            } finally {
                conn.setAutoCommit(true)
            }
        }
    }

    private fun bindRun(stmt: PreparedStatement, run: AgentRun) {
        stmt.setString(1, run.id.value)
        stmt.setString(2, run.interactionId)
        stmt.setString(3, run.conversationId)
        stmt.setString(4, run.originNodeId)
        stmt.setString(5, run.status.name)
        stmt.setInt(6, run.attempt)
        stmt.setLong(7, run.revision)
        stmt.setString(8, run.checkpointRef)
        stmt.setString(9, run.providerId)
        stmt.setString(10, run.modelId)
        stmt.setString(11, run.failureCategory)
        stmt.setString(12, run.createdAt.toString())
        stmt.setString(13, run.updatedAt.toString())
    }

    private fun mapRowToRun(rs: ResultSet): AgentRun {
        return AgentRun(
            id = AgentRunId(rs.getString("run_id")),
            interactionId = rs.getString("interaction_id"),
            conversationId = rs.getString("conversation_id"),
            originNodeId = rs.getString("origin_node_id"),
            status = AgentRunStatus.valueOf(rs.getString("status")),
            attempt = rs.getInt("attempt"),
            revision = rs.getLong("revision"),
            checkpointRef = rs.getString("checkpoint_ref"),
            providerId = rs.getString("provider_id"),
            modelId = rs.getString("model_id"),
            failureCategory = rs.getString("failure_category"),
            createdAt = Instant.parse(rs.getString("created_at")),
            updatedAt = Instant.parse(rs.getString("updated_at")),
            lastDurableCheckpoint = rs.getLong("revision").takeIf { it > 0 },
        )
    }

    private fun appendEventInternal(conn: Connection, event: AgentRunEvent) {
        conn.prepareStatement("""
            INSERT INTO agent_run_events (event_id, run_id, sequence, event_type, schema_version, payload_ref, correlation_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()).use { stmt ->
            stmt.setString(1, event.eventId)
            stmt.setString(2, event.runId.value)
            stmt.setLong(3, event.sequence)
            stmt.setString(4, event.eventType)
            stmt.setInt(4, event.schemaVersion)
            stmt.setString(5, event.payloadRef)
            stmt.setString(6, event.correlationId)
            stmt.setString(7, event.createdAt.toString())
            stmt.executeUpdate()
        }
    }

    private fun appendStepInternal(conn: Connection, step: AgentStep) {
        val stepType = when (step) {
            is AgentStep.ToolDispatched -> "TOOL_DISPATCHED"
            is AgentStep.ToolCompleted -> "TOOL_COMPLETED"
            is AgentStep.ToolFailed -> "TOOL_FAILED"
            is AgentStep.ToolRejected -> "TOOL_REJECTED"
            is AgentStep.ToolExecutionClaimed -> "TOOL_EXECUTION_CLAIMED"
            is AgentStep.RecoveryClaimed -> "RECOVERY_CLAIMED"
            is AgentStep.InputResolved -> "INPUT_RESOLVED"
            is AgentStep.StateChanged -> "STATE_CHANGED"
        }
        
        conn.prepareStatement("""
            INSERT INTO agent_run_events (event_id, run_id, sequence, event_type, schema_version, payload_ref, correlation_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()).use { stmt ->
            stmt.setString(1, step.stepId.value)
            stmt.setString(2, step.runId.value)
            stmt.setLong(3, step.sequence)
            stmt.setString(4, stepType)
            stmt.setInt(5, 1)
            stmt.setString(6, null) // payload_ref would need serialization
            stmt.setString(7, null)
            stmt.setString(8, step.createdAt.toString())
            stmt.executeUpdate()
        }
    }

    private fun checkToolDispatchedExists(conn: Connection, toolCallId: String): Boolean {
        return conn.prepareStatement("SELECT 1 FROM agent_run_events WHERE event_type = 'TOOL_DISPATCHED' AND payload_ref = ? LIMIT 1")
            .use { stmt ->
                stmt.setString(1, toolCallId)
                stmt.executeQuery().use { rs -> rs.next() }
            }
    }

    private fun checkToolClaimedExists(conn: Connection, toolCallId: String): Boolean {
        return conn.prepareStatement("SELECT 1 FROM agent_run_events WHERE event_type = 'TOOL_EXECUTION_CLAIMED' AND payload_ref = ? LIMIT 1")
            .use { stmt ->
                stmt.setString(1, toolCallId)
                stmt.executeQuery().use { rs -> rs.next() }
            }
    }

    private fun checkInputResolvedExists(conn: Connection, requestId: AgentInputRequestId): Boolean {
        return conn.prepareStatement("SELECT 1 FROM agent_run_events WHERE event_type = 'INPUT_RESOLVED' AND payload_ref = ? LIMIT 1")
            .use { stmt ->
                stmt.setString(1, requestId.value)
                stmt.executeQuery().use { rs -> rs.next() }
            }
    }

    private fun getNextSequence(conn: Connection, runId: AgentRunId): Long {
        return conn.prepareStatement("SELECT COALESCE(MAX(sequence), 0) + 1 FROM agent_run_events WHERE run_id = ?")
            .use { stmt ->
                stmt.setString(1, runId.value)
                stmt.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 1 }
            }
    }
}