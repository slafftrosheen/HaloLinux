package com.halo.icl.ontology

import com.halo.icl.core.*
import kotlinx.datetime.Instant

data class Task(
    override val id: CognitiveId,
    override val created: Instant,
    override val confidence: Confidence,
    override val provenance: Provenance,
    override val edges: List<CognitiveEdge>,
    val capabilityId: String,
    val payload: Any?
) : CognitiveObject
