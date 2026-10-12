package com.halo.sdk.runtime

import java.util.UUID

data class PlannedAction(
    val id: UUID,
    val plugin: String,
    val operation: String,
    val parameters: Map<String, Any>
)

data class ActionPlan(
    val actions: List<PlannedAction>
)

data class PlanCreated(
    override val id: String = UUID.randomUUID().toString(),
    val plan: ActionPlan,
    override val timestamp: Long = System.currentTimeMillis()
) : HaloEvent
