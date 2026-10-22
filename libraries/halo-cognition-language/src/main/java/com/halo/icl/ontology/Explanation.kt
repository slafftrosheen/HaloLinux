package com.halo.icl.ontology

import com.halo.icl.core.*
import kotlinx.datetime.Instant

data class Explanation(
    override val id: CognitiveId,
    override val created: Instant,
    override val confidence: Confidence,
    override val provenance: Provenance,
    override val edges: List<CognitiveEdge>,
    val targetId: CognitiveId, // The object being explained
    val reason: String // Human-readable rationale extracted from the graph
) : CognitiveObject
