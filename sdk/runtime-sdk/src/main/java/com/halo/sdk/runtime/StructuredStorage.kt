package com.halo.sdk.runtime

/**
 * Extended storage interface for structured conversation, memory, and identity data.
 * 
 * This is distinct from the simple key-value [Storage] interface which is used
 * for runtime preferences. Implementations should provide durable, queryable
 * storage for conversations, memories, and node identity.
 */
interface StructuredStorage {
    suspend fun initialize(config: StructuredStorageConfig)
    
    suspend fun getDataDirectory(): String
    suspend fun getConfigDirectory(): String
    suspend fun getCacheDirectory(): String
    
    // Conversations
    suspend fun storeConversation(conversation: ConversationRecord)
    suspend fun loadConversation(id: String): ConversationRecord?
    suspend fun listConversations(limit: Int = 50, offset: Int = 0): List<ConversationSummary>
    
    // Memories
    suspend fun searchMemories(query: String, limit: Int = 20): List<MemoryRecord>
    suspend fun storeMemory(memory: MemoryRecord)
    
    // Node Identity
    suspend fun getNodeIdentity(): NodeIdentity
    suspend fun setNodeIdentity(identity: NodeIdentity)
    
    suspend fun close()
}

/**
 * Configuration for structured storage.
 */
data class StructuredStorageConfig(
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
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant,
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
    val createdAt: java.time.Instant,
    val metadata: String?,
)

/**
 * Lightweight conversation summary for listings.
 */
data class ConversationSummary(
    val id: String,
    val title: String,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant,
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
    val expiresAt: java.time.Instant?,
    val createdAt: java.time.Instant,
    val updatedAt: java.time.Instant,
    val metadata: String?,
)

/**
 * Persistent node identity for sync.
 * 
 * Note: The privateKey field is stored in plaintext in SQLite.
 * This is NOT a cryptographic identity suitable for secure pairing.
 * Secure key storage and pairing protocols are out of scope.
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