package com.halo.icl.core

data class Provenance(
    val generatorId: String, // Which agent or engine created this
    val parentId: CognitiveId? // The antecedent object, forming a causal chain
)
