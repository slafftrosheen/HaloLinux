// Re-export payload types from Goal.kt
// They are already defined in com.halo.icl.ontology.Goal

@Deprecated("Payload types are now in Goal.kt", ReplaceWith("import com.halo.icl.ontology.GoalPayload"))
typealias Payloads = com.halo.icl.ontology.GoalPayload