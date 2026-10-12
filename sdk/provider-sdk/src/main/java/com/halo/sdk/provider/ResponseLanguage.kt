package com.halo.sdk.provider

/** A bounded presentation preference, never a source of tool authority. */
fun responseLanguageInstruction(tag: String?): String? {
    val language = when (tag?.lowercase()?.substringBefore('-')) {
        "en" -> "English"
        "ru" -> "Russian"
        "lv" -> "Latvian"
        else -> return null
    }
    return "Default response language: $language. Use this language for replies and human-readable values unless the user explicitly requests another language or the task specifies an explanation language. Preserve quotations, code, JSON keys, tool names and source identifiers."
}

fun AiContext.withResponseLanguage(tag: String?): AiContext {
    val instruction = responseLanguageInstruction(tag) ?: return this
    return copy(conversation = listOf(AIMessage(Role.SYSTEM, instruction)) + conversation)
}
