package com.halo.host.linux

import com.halo.adapters.storage.LinuxStorage
import com.halo.adapters.storage.MemoryRecord
import com.halo.sdk.runtime.Query
import com.halo.sdk.runtime.QueryBus

/** Explicit typed retrieval. No model may invoke this automatically. */
data class SearchLocalMemory(val text: String, val limit: Int = 10) : Query<List<MemoryRecord>>

/** Content-free health count, safe for local diagnostics. */
data object CountInterruptedTextTurns : Query<Long>

/**
 * Linux host implementation of the portable QueryBus contract.
 * Retrieval is local, read-only, and limited to approved, typed queries.
 */
class LinuxKnowledgeQueries(private val storage: LinuxStorage) : QueryBus {
    @Suppress("UNCHECKED_CAST")
    override suspend fun <R> execute(query: Query<R>): R {
        val result: Any = when (query) {
            is SearchLocalMemory -> {
                require(query.text.isNotBlank() && query.text.length <= 1000)
                require(query.limit in 1..100)
                storage.searchMemories(query.text, query.limit)
            }
            CountInterruptedTextTurns -> storage.countInterruptedTurns()
            else -> throw UnsupportedOperationException(
                "No Linux host implementation for query ${query::class.simpleName}"
            )
        }
        return result as R
    }
}
