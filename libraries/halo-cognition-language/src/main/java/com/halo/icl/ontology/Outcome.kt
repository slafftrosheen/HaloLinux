package com.halo.icl.ontology

import com.halo.icl.core.*
import kotlinx.datetime.Instant

data class Outcome(
    override val id: CognitiveId,
    override val created: Instant,
    override val confidence: Confidence,
    override val provenance: Provenance, // Provenance points back to the Task
    override val edges: List<CognitiveEdge>,
    val success: Boolean,
    val resultPayload: Any?
) : CognitiveObject
