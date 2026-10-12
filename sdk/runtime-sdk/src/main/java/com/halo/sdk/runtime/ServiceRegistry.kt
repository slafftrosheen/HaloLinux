package com.halo.sdk.runtime

interface ServiceRegistry {
    fun <T : Any> register(key: ServiceKey<T>, service: T)
    fun <T : Any> resolve(key: ServiceKey<T>): T
}
