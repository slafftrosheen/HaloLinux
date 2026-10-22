package com.halo.cognition.language

data class NativeGoogleMailIntent(val query: String? = null, val messageId: String? = null) : NativeControlIntent

internal object NativeGoogleMailParser {
    fun parse(raw: String): NativeControlIntent? {
        val command = raw.lowercase(java.util.Locale.ROOT)
        if (command in setOf("read my gmail inbox", "read gmail inbox", "parādi manu gmail iesūtni", "покажи мою почту gmail"))
            return NativeGoogleMailIntent(query = "in:inbox")
        for (prefix in listOf("search gmail for ", "meklē gmail ", "найди в gmail ")) {
            if (command.startsWith(prefix)) {
                val query = raw.substring(prefix.length).trim()
                return if (query.isNotBlank() && query.length <= 500) NativeGoogleMailIntent(query = query)
                else NativeClarificationIntent("Specify a Gmail search up to 500 characters.")
            }
        }
        return null
    }
}
