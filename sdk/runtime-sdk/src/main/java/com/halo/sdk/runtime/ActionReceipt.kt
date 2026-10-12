package com.halo.sdk.runtime

import java.util.UUID

data class ActionReceipt(
    override val id: String = UUID.randomUUID().toString(),
    override val timestamp: Long = System.currentTimeMillis(),
    val operationId: String,
    val category: String,
    val title: String,
    val stage: String,
    val detail: String,
    val sourceDeviceId: String? = null,
    val objectId: String? = null
) : HaloEvent
