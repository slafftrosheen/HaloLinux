package com.halo.sdk.runtime

interface Query<R>

interface QueryBus {
    suspend fun <R> execute(query: Query<R>): R
}
