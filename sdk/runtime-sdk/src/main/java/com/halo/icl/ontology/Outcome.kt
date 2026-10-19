package com.halo.icl.ontology

import java.time.Instant
import java.util.UUID

data class Outcome(
    val id: String = UUID.randomUUID().toString(),
    val created: Instant = Instant.now(),
    val confidence: Double = 1.0,
    val provenance: String = "unknown",
    val edges: List<String> = emptyList(),
    val success: Boolean,
    val resultPayload: Any? = null
)