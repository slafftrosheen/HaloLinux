package com.halo.sdk.provider

/**
 * Provider-layer secret persistence contract.
 *
 * This mirrors the app's SecretStore shape so provider modules can persist
 * OAuth/companion secrets without depending on the app module (providers must
 * never depend on :app). The app wires its Android keystore-backed store into
 * this contract at composition time.
 *
 * Values are secrets; implementations must never log them.
 */
interface ProviderSecretStore {
    suspend fun put(key: String, value: String?)
    suspend fun get(key: String): String?
    suspend fun remove(key: String)
}
