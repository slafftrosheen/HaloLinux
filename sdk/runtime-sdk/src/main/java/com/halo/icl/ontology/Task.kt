package com.halo.icl.ontology

import java.time.Instant
import java.util.UUID

data class Task(
    val id: String = UUID.randomUUID().toString(),
    val created: Instant = Instant.now(),
    val confidence: Double = 1.0,
    val provenance: String = "unknown",
    val edges: List<String> = emptyList(),
    val capabilityId: String,
    val payload: Any? = null
)