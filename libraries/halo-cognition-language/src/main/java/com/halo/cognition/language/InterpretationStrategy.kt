package com.halo.cognition.language

sealed interface InterpretationStrategy {
    data object Deterministic : InterpretationStrategy
    data object Contextual : InterpretationStrategy
    data object Semantic : InterpretationStrategy
}
