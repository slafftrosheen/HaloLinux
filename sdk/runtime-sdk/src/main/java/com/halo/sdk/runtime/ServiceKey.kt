package com.halo.sdk.runtime

import kotlin.reflect.KClass

data class ServiceKey<T : Any>(
    val type: KClass<T>,
    val name: String = type.simpleName ?: "Unknown"
)
