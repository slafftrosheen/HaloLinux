package com.halo.host.linux

import com.halo.adapters.storage.MemoryRecord
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class LinuxMemoryResponsesTest {
    private fun memory(id: String, content: String): MemoryRecord {
        val now = Instant.now()
        return MemoryRecord(id, content, "test", "personal", 1.0,
            null, now, now, "{}")
    }

    @Test fun longNotesReturnMarkedExcerptsInsteadOfBreakingIpc() {
        val source = memory("long-note", "Hello ".repeat(20_000))
        val result = LinuxMemoryResponses.searchResults(listOf(source))
        val item = result.getJSONArray("results").getJSONObject(0)
        assertEquals("long-note", item.getString("id"))
        assertTrue(item.getBoolean("content_truncated"))
        assertEquals(1, result.getInt("excerpted"))
        assertTrue(item.getString("content").length <= 2048)
        assertTrue(result.toString().toByteArray(Charsets.UTF_8).size < 131_072)
    }

    @Test fun multiByteSearchResultsNeverExceedLocalFrameBudget() {
        val notes = (1..20).map { memory("id-$it", "🙂".repeat(10_000)) }
        val result = LinuxMemoryResponses.searchResults(notes)
        assertTrue(result.toString().toByteArray(Charsets.UTF_8).size < 131_072)
        assertEquals(20, result.getInt("returned") + result.getInt("omitted_due_to_size"))
    }
}
