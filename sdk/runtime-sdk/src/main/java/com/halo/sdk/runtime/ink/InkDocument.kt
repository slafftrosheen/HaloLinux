package com.halo.sdk.runtime.ink

/** Integer thousandths of the source rectangle; times are relative to contact start. */
data class InkPoint(
    val x: Int, val y: Int, val timeMs: Int = 0,
    val pressure: Int? = null, val tilt: Int? = null, val orientation: Int? = null,
    val buttons: Int = 0,
)

/** Erasing is an editing transaction, never a white stroke baked into a document. */
enum class InkTool(val wire: String) { LEGACY("L"), FINGER("F"), STYLUS("S"), MOUSE("M") }
data class InkStroke(val tool: InkTool, val points: List<InkPoint>, val strokeId: String = InkStrokeIds.create())
internal object InkStrokeIds {
    fun create(): String = java.util.UUID.randomUUID().toString()
}
/** Zero aspect preserves v1's rectangle-relative rendering; new sketches use 7:5. */
data class InkDocument(val strokes: List<InkStroke> = emptyList(), val aspectMilli: Int = 1400)

object InkLimits {
    const val MAX_STROKES = 256
    const val MAX_POINTS_PER_STROKE = 2048
    const val MAX_TOTAL_POINTS = 16384
    const val MAX_ENCODED_CHARS = 262_144
    const val MAX_CONTACT_MS = 3_600_000
    const val MAX_HISTORY = 16
    const val MAX_HISTORY_POINTS = 65536

    fun valid(point: InkPoint): Boolean = point.x in 0..1000 && point.y in 0..1000 &&
        point.timeMs in 0..MAX_CONTACT_MS && (point.pressure == null || point.pressure in 0..1000) &&
        (point.tilt == null || point.tilt in 0..1571) &&
        (point.orientation == null || point.orientation in -3142..3142) && point.buttons in 0..65535

    fun requireValid(document: InkDocument) {
        require(document.aspectMilli == 0 || document.aspectMilli in 125..8000)
        require(document.strokes.size <= MAX_STROKES)
        require(document.strokes.map { it.strokeId }.distinct().size == document.strokes.size)
        var total = 0
        document.strokes.forEach { stroke ->
            require(stroke.strokeId.length in 1..128 && stroke.strokeId.matches(Regex("[A-Za-z0-9_-]+")))
            require(stroke.points.size in 1..MAX_POINTS_PER_STROKE)
            total += stroke.points.size
            require(total <= MAX_TOTAL_POINTS)
            var previous = -1
            stroke.points.forEach { point ->
                require(valid(point) && point.timeMs >= previous)
                if (stroke.tool == InkTool.LEGACY) require(point == InkPoint(point.x, point.y))
                previous = point.timeMs
            }
        }
    }
}
