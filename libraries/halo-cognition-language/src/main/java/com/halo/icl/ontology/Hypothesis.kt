package com.halo.icl.ontology

import com.halo.icl.core.*
import kotlinx.datetime.Instant

data class Hypothesis(
    override val id: CognitiveId,
    override val created: Instant,
    override val confidence: Confidence, // Usually low
    override val provenance: Provenance,
    override val edges: List<CognitiveEdge>,
    val theory: String // e.g., "Maybe user is driving"
) : CognitiveObject
