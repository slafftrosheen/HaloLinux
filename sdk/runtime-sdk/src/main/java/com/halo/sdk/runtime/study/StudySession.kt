package com.halo.sdk.runtime.study

/**
 * Guided teaching progression.
 *
 * The order is a policy, not an automatic march: a learner may submit an attempt at any stage, ask
 * to repeat a hint, request a simpler explanation, change language, step back or explicitly ask for
 * the full solution. The stage actually used is persisted so progress reporting never has to guess.
 */
enum class TutorStage {
    ASK,
    HINT,
    EXPLAIN,
    ANALOGY,
    CHECK_ATTEMPT,
    PARTIAL_SOLUTION,
    SOLUTION;

    val isSolution: Boolean get() = this == SOLUTION

    /** The next guided step, or null at the end of the progression. */
    fun next(): TutorStage? = entries.getOrNull(ordinal + 1)

    fun previous(): TutorStage? = entries.getOrNull(ordinal - 1)

    /** How far along the guided path this stage is; used for progress, never for grading. */
    val progressIndex: Int get() = ordinal

    /** Learner-facing description of what this stage offers. */
    val guidance: String
        get() = when (this) {
            ASK -> "Understand the task and what you already know"
            HINT -> "One nudge, without the answer"
            EXPLAIN -> "Explain the key idea"
            ANALOGY -> "A different angle or analogy"
            CHECK_ATTEMPT -> "Check my attempt and show the step that needs work"
            PARTIAL_SOLUTION -> "Work through the next step together"
            SOLUTION -> "Show the full worked solution"
        }
}

enum class StudySessionLifecycle { ACTIVE, COMPLETED, ARCHIVED }

/**
 * Three independent language preferences. The learner's strongest language is deliberately separate
 * from the assignment and explanation languages: switching explanation language must not rewrite the
 * assignment's terms.
 */
data class StudyLanguagePreferences(
    val assignmentLanguage: String? = null,
    val explanationLanguage: String? = null,
    val strongestLanguage: String? = null,
) {
    val isComplete: Boolean
        get() = !explanationLanguage.isNullOrBlank() && !strongestLanguage.isNullOrBlank()
}

/**
 * The portable Study session contract. Persistence lives in the app layer; this type is shared so
 * the runtime can reason about a session without importing Android.
 */
enum class StudyLearnerLevel(val wordLimit: Int) { EARLY_PRIMARY(80), PRIMARY(120), LOWER_SECONDARY(160), UPPER_SECONDARY(220), ADULT(240) }
enum class StudySupportStyle { GUIDED, PRACTICE, EXPLAIN }
enum class StudyKnowledgePolicy { SOURCE_ONLY, SOURCE_PLUS_GENERAL }
data class StudySessionSource(val objectId: String, val revision: String? = null)

data class StudySession(
    val id: String,
    val assignmentLanguage: String?,
    val explanationLanguage: String?,
    val sourceObjectIds: List<String>,
    val interactionId: String?,
    val tutorStage: TutorStage,
    val strongestLanguage: String? = null,
    val goal: String? = null,
    val sourceRevision: Long? = null,
    val conversationId: String? = null,
    val runId: String? = null,
    val lifecycle: StudySessionLifecycle = StudySessionLifecycle.ACTIVE,
    val currentExerciseId: String? = null,
    val solutionRevealed: Boolean = false,
    val createdAtEpochMs: Long = 0,
    val updatedAtEpochMs: Long = 0,
    val schemaVersion: Int = SCHEMA_VERSION,
    val sources: List<StudySessionSource> = sourceObjectIds.mapIndexed { index, id -> StudySessionSource(id, if (index == 0) sourceRevision?.toString() else null) },
    val learnerLevel: StudyLearnerLevel = StudyLearnerLevel.ADULT,
    val supportStyle: StudySupportStyle = StudySupportStyle.GUIDED,
    val knowledgePolicy: StudyKnowledgePolicy = StudyKnowledgePolicy.SOURCE_ONLY,
) {
    val languages: StudyLanguagePreferences
        get() = StudyLanguagePreferences(assignmentLanguage, explanationLanguage, strongestLanguage)

    val isResumable: Boolean
        get() = lifecycle == StudySessionLifecycle.ACTIVE

    /**
     * Completion is a separate fact from revealing the solution: a learner can finish a session
     * without ever seeing the answer, and can see the answer without finishing.
     */
    val isCompleted: Boolean get() = lifecycle == StudySessionLifecycle.COMPLETED

    companion object {
        const val SCHEMA_VERSION = 3
    }
}
