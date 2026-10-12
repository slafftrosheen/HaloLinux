package com.halo.sdk.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

data class HaloDispatchers(
    val io: CoroutineDispatcher,
    val default: CoroutineDispatcher,
    val main: CoroutineDispatcher
)

fun productionHaloDispatchers(): HaloDispatchers = HaloDispatchers(
    io = Dispatchers.IO,
    default = Dispatchers.Default,
    main = runCatching { Dispatchers.Main.immediate }.getOrElse { Dispatchers.Default }
)
