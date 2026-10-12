package com.halo.sdk.runtime

/**
 * Interface for simple key-value persistence.
 *
 * Used by the runtime and drivers to persist state across app restarts,
 * such as the last connected device address.
 */
interface Storage {
    fun getString(key: String, defaultValue: String? = null): String?
    fun putString(key: String, value: String?)
    
    fun getInt(key: String, defaultValue: Int = 0): Int
    fun putInt(key: String, value: Int)
    
    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean
    fun putBoolean(key: String, value: Boolean)

    /** Commits related preference values as one logical update when the backend supports it. */
    fun putBatch(
        strings: Map<String, String?> = emptyMap(),
        booleans: Map<String, Boolean> = emptyMap(),
    ) {
        strings.forEach { (key, value) -> putString(key, value) }
        booleans.forEach { (key, value) -> putBoolean(key, value) }
    }
    
    fun remove(key: String)
    fun clear()
}
