package com.halo.icl.ontology

import com.halo.icl.core.*
import kotlinx.datetime.Instant

data class Plan(
    override val id: CognitiveId,
    override val created: Instant,
    override val confidence: Confidence,
    override val provenance: Provenance,
    override val edges: List<CognitiveEdge>,
    val intent: String? = null,
    val taskIds: List<CognitiveId> // Ordered sequence of Tasks to execute
) : CognitiveObject
