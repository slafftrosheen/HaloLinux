package com.halo.host.linux

import com.halo.adapters.storage.MemoryRecord
import org.json.JSONArray
import org.json.JSONObject

/** Bounded, explicit excerpts for the 128 KiB local control protocol. */
internal object LinuxMemoryResponses {
    private const val MAX_EXCERPT_CHARS = 2048
    private const val MAX_RESPONSE_BYTES = 96 * 1024

    fun searchResults(hits: List<MemoryRecord>): JSONObject {
        val entries = JSONArray()
        var omitted = 0
        var excerpted = 0
        for (hit in hits.take(20)) {
            val content = hit.content.take(MAX_EXCERPT_CHARS)
            val clipped = content.length < hit.content.length
            val entry = JSONObject().put("id", hit.id).put("content", content)
                .put("content_truncated", clipped)
            // Measure serialized JSON rather than UTF-16 length so long
            // multi-byte notes do not overflow IPC.
            val prospective = JSONObject().put("ok", true).put("results", JSONArray(entries.toString()).put(entry))
                .toString().toByteArray(Charsets.UTF_8).size
            if (prospective > MAX_RESPONSE_BYTES) {
                omitted++
                continue
            }
            entries.put(entry)
            if (clipped) excerpted++
        }
        return JSONObject().put("ok", true).put("results", entries)
            .put("returned", entries.length())
            .put("omitted_due_to_size", omitted)
            .put("excerpted", excerpted)
    }
}
