package com.halo.icl.ontology

import com.halo.icl.core.*
import kotlinx.datetime.Instant

enum class GoalType {
    CONVERSATION, MEDIA, NAVIGATION, DEVICE, MEMORY, SEARCH, AUTOMATION, COMMUNICATION
}

enum class Priority {
    CRITICAL, HIGH, NORMAL, BACKGROUND
}

interface GoalPayload

data class ConversationPayload(val topic: String? = null) : GoalPayload
data class NavigationPayload(val destination: String) : GoalPayload
data class MediaPayload(val query: String, val action: String = "play") : GoalPayload
data class DevicePayload(val action: String, val target: String, val parameters: Map<String, Any> = emptyMap()) : GoalPayload
data class AutomationPayload(val routine: String) : GoalPayload
data class SearchPayload(val query: String) : GoalPayload
data class MemoryPayload(val fact: String) : GoalPayload
data class CommunicationPayload(val target: String, val message: String? = null) : GoalPayload

data class Goal(
    override val id: CognitiveId,
    override val created: Instant,
    override val confidence: Confidence,
    override val provenance: Provenance,
    override val edges: List<CognitiveEdge>,
    val type: GoalType,
    val priority: Priority = Priority.NORMAL,
    val payload: GoalPayload
) : CognitiveObject
