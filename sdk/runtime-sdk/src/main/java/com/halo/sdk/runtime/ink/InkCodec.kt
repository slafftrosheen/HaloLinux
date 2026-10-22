package com.halo.sdk.runtime.ink

/** Strict, bounded text format. A corrupt/future payload is null, not an editable empty page. */
object InkCodec {
    private const val V1 = "sketch:v1|"
    private const val V2 = "sketch:v2|"
    private const val V3 = "sketch:v3|"

    fun encode(document: InkDocument): String {
        InkLimits.requireValid(document)
        return buildString {
            append(V3).append(document.aspectMilli)
            document.strokes.forEach { stroke ->
                append('|').append(stroke.tool.wire).append('@').append(stroke.strokeId).append('@')
                stroke.points.forEachIndexed { index, p ->
                    if (index > 0) append(';')
                    append(p.x).append(',').append(p.y)
                    if (stroke.tool == InkTool.LEGACY) return@forEachIndexed
                    append(',').append(p.timeMs).append(',')
                    append(p.pressure?.toString() ?: "_").append(',')
                    append(p.tilt?.toString() ?: "_").append(',')
                    append(p.orientation?.toString() ?: "_").append(',').append(p.buttons)
                }
            }
        }.also { require(it.length <= InkLimits.MAX_ENCODED_CHARS) }
    }

    fun decode(encoded: String): InkDocument? {
        if (encoded.isEmpty()) return InkDocument()
        if (encoded.length > InkLimits.MAX_ENCODED_CHARS) return null
        return try {
            when {
                encoded.startsWith(V1) -> legacy(encoded)
                encoded.startsWith(V2) -> versionTwo(encoded)
                encoded.startsWith(V3) -> versionThree(encoded)
                else -> null
            }
        } catch (_: IllegalArgumentException) { null }
    }

    private fun legacy(encoded: String): InkDocument? {
        if (encoded.length > 200_000) return null
        val body = encoded.substring(V1.length)
        if (body.isBlank()) return InkDocument(aspectMilli = 0)
        val rows = body.split('|', limit = 66)
        if (rows.size > 64) return null
        val strokes = rows.mapIndexed { index, row ->
            val points = row.split(';', limit = 258)
            if (points.size > 256) return null
            InkStroke(InkTool.LEGACY, points.map { value ->
                val fields = value.split(',', limit = 3)
                if (fields.size != 2) return null
                val x = fields[0].toIntOrNull() ?: return null
                val y = fields[1].toIntOrNull() ?: return null
                InkPoint(x, y).also { if (!InkLimits.valid(it)) return null }
            }, "legacy-$index")
        }
        return InkDocument(strokes, 0).also(InkLimits::requireValid)
    }

    private fun versionTwo(encoded: String): InkDocument? {
        val rows = encoded.substring(V2.length).split('|', limit = InkLimits.MAX_STROKES + 2)
        if (rows.size > InkLimits.MAX_STROKES + 1) return null
        val aspect = rows.first().toIntOrNull() ?: return null
        if (aspect != 0 && aspect !in 125..8000) return null
        var total = 0
        val strokes = rows.drop(1).mapIndexed { index, row ->
            if (row.length < 3 || row[1] != '@') return null
            val tool = InkTool.entries.firstOrNull { it.wire == row.substring(0, 1) } ?: return null
            val points = row.substring(2).split(';', limit = InkLimits.MAX_POINTS_PER_STROKE + 1)
            if (points.size > InkLimits.MAX_POINTS_PER_STROKE) return null
            total += points.size
            if (total > InkLimits.MAX_TOTAL_POINTS) return null
            var previous = -1
            InkStroke(tool, points.map pointMap@ { value ->
                val fields = value.split(',', limit = 8)
                if (tool == InkTool.LEGACY) {
                    if (fields.size != 2) return null
                    val point = InkPoint(fields[0].toIntOrNull() ?: return null, fields[1].toIntOrNull() ?: return null)
                    if (!InkLimits.valid(point)) return null
                    return@pointMap point
                }
                if (fields.size != 7) return null
                // An underscore alone denotes an unavailable sensor; invalid numbers never become null.
                val optional = (3..5).map { index ->
                    if (fields[index] == "_") null else { fields[index].toIntOrNull() ?: return null }
                }
                val point = InkPoint(fields[0].toIntOrNull() ?: return null,
                    fields[1].toIntOrNull() ?: return null, fields[2].toIntOrNull() ?: return null,
                    optional[0], optional[1], optional[2], fields[6].toIntOrNull() ?: return null)
                if (!InkLimits.valid(point) || point.timeMs < previous) return null
                previous = point.timeMs
                point
            }, "v2-$index")
        }
        return InkDocument(strokes, aspect)
    }

    private fun versionThree(encoded: String): InkDocument? {
        val rows = encoded.substring(V3.length).split('|', limit = InkLimits.MAX_STROKES + 2)
        if (rows.size > InkLimits.MAX_STROKES + 1) return null
        val aspect = rows.first().toIntOrNull() ?: return null
        if (aspect != 0 && aspect !in 125..8000) return null
        var total = 0
        val strokes = rows.drop(1).map { row ->
            val fields = row.split('@', limit = 3)
            if (fields.size != 3) return null
            val tool = InkTool.entries.firstOrNull { it.wire == fields[0] } ?: return null
            val id = fields[1]
            if (id.length !in 1..128 || !id.matches(Regex("[A-Za-z0-9_-]+"))) return null
            val points = fields[2].split(';', limit = InkLimits.MAX_POINTS_PER_STROKE + 1)
            if (points.isEmpty() || points.size > InkLimits.MAX_POINTS_PER_STROKE) return null
            total += points.size
            if (total > InkLimits.MAX_TOTAL_POINTS) return null
            var previous = -1
            val decoded = points.map pointMap@ { value ->
                val pointFields = value.split(',', limit = 8)
                if (tool == InkTool.LEGACY) {
                    if (pointFields.size != 2) return null
                    val point = InkPoint(pointFields[0].toIntOrNull() ?: return null,
                        pointFields[1].toIntOrNull() ?: return null)
                    if (!InkLimits.valid(point)) return null
                    return@pointMap point
                }
                if (pointFields.size != 7) return null
                val optional = (3..5).map { fieldIndex ->
                    if (pointFields[fieldIndex] == "_") null
                    else pointFields[fieldIndex].toIntOrNull() ?: return null
                }
                val point = InkPoint(pointFields[0].toIntOrNull() ?: return null,
                    pointFields[1].toIntOrNull() ?: return null,
                    pointFields[2].toIntOrNull() ?: return null,
                    optional[0], optional[1], optional[2], pointFields[6].toIntOrNull() ?: return null)
                if (!InkLimits.valid(point) || point.timeMs < previous) return null
                previous = point.timeMs
                point
            }
            InkStroke(tool, decoded, id)
        }
        return InkDocument(strokes, aspect).also(InkLimits::requireValid)
    }
}
