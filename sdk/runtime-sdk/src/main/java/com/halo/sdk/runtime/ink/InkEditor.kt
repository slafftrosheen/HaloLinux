package com.halo.sdk.runtime.ink

import kotlin.math.max
import kotlin.math.min

/** Single-owner, portable editing state. A contact is one undoable transaction. */
class InkEditor(initial: InkDocument, private val editAspectMilli: Int? = null) {
    init { require(editAspectMilli == null || editAspectMilli in 125..8000) }
    var document: InkDocument = initial.also(InkLimits::requireValid).let { doc ->
        doc.copy(strokes = doc.strokes.map { it.copy(points = it.points.toList()) })
    }
        private set
    private val past = ArrayDeque<InkDocument>()
    private val future = ArrayDeque<InkDocument>()
    private val points = ArrayList<InkPoint>()
    private var working: InkDocument? = null
    private var tool: InkTool? = null
    private var activeStrokeId: String? = null
    private var erasing = false
    private var previousErase: InkPoint? = null
    var limitReached = false
        private set
    val active: Boolean get() = tool != null
    val activeTool: InkTool? get() = tool
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun begin(tool: InkTool, erasing: Boolean = false): Boolean {
        cancel()
        limitReached = false
        if (!erasing && (document.strokes.size >= InkLimits.MAX_STROKES ||
                document.strokes.sumOf { it.points.size } >= InkLimits.MAX_TOTAL_POINTS)) {
            limitReached = true
            return false
        }
        this.tool = tool; this.erasing = erasing; working = document
        activeStrokeId = if (erasing) null else InkStrokeIds.create()
        return true
    }

    fun sample(point: InkPoint, radiusX: Float = 20f, radiusY: Float = 20f): Boolean {
        if (!active || !InkLimits.valid(point)) return false
        if (erasing) {
            if (!radiusX.isFinite() || !radiusY.isFinite() || radiusX <= 0f || radiusY <= 0f) return false
            val current = working ?: return false
            val from = previousErase ?: point
            val kept = current.strokes.filterNot { InkGeometry.intersects(it, from, point, radiusX, radiusY) }
            previousErase = point
            if (kept.size == current.strokes.size) return false
            working = current.copy(strokes = kept)
            return true
        }
        val last = points.lastOrNull()
        if (last != null && point.timeMs < last.timeMs) return false
        if (last != null && last.copy(timeMs = point.timeMs) == point) return false
        if (points.size >= InkLimits.MAX_POINTS_PER_STROKE ||
            points.size + document.strokes.sumOf { it.points.size } >= InkLimits.MAX_TOTAL_POINTS) {
            limitReached = true
            return false
        }
        points += point
        return true
    }

    fun preview(): InkDocument = if (erasing && active) working ?: document else
        if (points.isNotEmpty() && tool != null) document.copy(strokes = document.strokes +
            InkStroke(tool!!, points.toList(), requireNotNull(activeStrokeId))) else document

    fun finish(): Boolean {
        if (!active) return false
        val next = preview()
        cancel()
        return commit(next)
    }

    /** Platform palm/navigation cancellation rolls back drawing AND erasing. */
    fun cancel() { points.clear(); working = null; tool = null; activeStrokeId = null; previousErase = null; erasing = false }

    fun clear(): Boolean { cancel(); limitReached = false; return commit(document.copy(strokes = emptyList())) }
    fun undo(): Boolean {
        cancel()
        if (past.isEmpty()) return false
        future.addLast(document); document = past.removeLast(); trim(future)
        limitReached = false
        return true
    }
    fun redo(): Boolean {
        cancel()
        if (future.isEmpty()) return false
        past.addLast(document); document = future.removeLast(); trim(past)
        limitReached = false
        return true
    }
    private fun commit(next: InkDocument): Boolean {
        if (next == document) return false
        InkLimits.requireValid(next)
        past.addLast(document); trim(past); future.clear()
        // Bind legacy geometry to the editing rectangle only after an actual edit. Undo
        // restores the old zero-aspect document, and untouched v1 never migrates.
        document = if (next.aspectMilli == 0 && editAspectMilli != null) next.copy(aspectMilli = editAspectMilli) else next
        return true
    }
    private fun trim(history: ArrayDeque<InkDocument>) {
        var count = history.sumOf { doc -> doc.strokes.sumOf { it.points.size } }
        while (history.size > InkLimits.MAX_HISTORY || count > InkLimits.MAX_HISTORY_POINTS) {
            count -= history.removeFirst().strokes.sumOf { it.points.size }
        }
    }
}

/** Swept elliptical whole-stroke eraser: sparse samples cannot skip a crossing segment. */
object InkGeometry {
    fun intersects(stroke: InkStroke, from: InkPoint, to: InkPoint, radiusX: Float, radiusY: Float): Boolean {
        if (radiusX <= 0f || radiusY <= 0f || !radiusX.isFinite() || !radiusY.isFinite()) return false
        fun scaled(p: InkPoint) = P(p.x.toDouble() / radiusX, p.y.toDouble() / radiusY)
        val a = scaled(from); val b = scaled(to)
        if (stroke.points.size == 1) return distance(scaled(stroke.points.first()), a, b) <= 1.0
        for (i in 1 until stroke.points.size) {
            val c = scaled(stroke.points[i - 1]); val d = scaled(stroke.points[i])
            if (crosses(a, b, c, d) || min(min(distance(a, c, d), distance(b, c, d)),
                    min(distance(c, a, b), distance(d, a, b))) <= 1.0) return true
        }
        return false
    }
    private data class P(val x: Double, val y: Double)
    private fun distance(p: P, a: P, b: P): Double {
        val dx = b.x - a.x; val dy = b.y - a.y
        val denominator = dx * dx + dy * dy
        val t = if (denominator == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / denominator).coerceIn(0.0, 1.0)
        val x = p.x - a.x - t * dx; val y = p.y - a.y - t * dy
        return x * x + y * y
    }
    private fun crosses(a: P, b: P, c: P, d: P): Boolean {
        fun side(p: P, q: P, r: P) = (q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)
        if (max(a.x, b.x) < min(c.x, d.x) || max(c.x, d.x) < min(a.x, b.x) ||
            max(a.y, b.y) < min(c.y, d.y) || max(c.y, d.y) < min(a.y, b.y)) return false
        return side(a, b, c) * side(a, b, d) <= 0.0 && side(c, d, a) * side(c, d, b) <= 0.0
    }

    /** Select a whole stroke when any segment or point touches a normalized document region. */
    fun intersects(stroke: InkStroke, bounds: InkSelectionBounds): Boolean {
        fun inside(point: InkPoint) = point.x in bounds.left..bounds.right && point.y in bounds.top..bounds.bottom
        if (stroke.points.any(::inside)) return true
        val corners = listOf(
            InkPoint(bounds.left, bounds.top), InkPoint(bounds.right, bounds.top),
            InkPoint(bounds.right, bounds.bottom), InkPoint(bounds.left, bounds.bottom),
        )
        if (stroke.points.size < 2) return false
        return stroke.points.zipWithNext().any { (from, to) ->
            (0..3).any { index -> crosses(from.toP(), to.toP(), corners[index].toP(), corners[(index + 1) % 4].toP()) }
        }
    }

    private fun InkPoint.toP() = P(x.toDouble(), y.toDouble())
}

/** Build and resolve region selections against the exact document revision that owns them. */
object InkSelections {
    fun create(
        document: InkDocument,
        workspaceObjectId: String,
        pageId: String?,
        sourceRevision: String,
        bounds: InkSelectionBounds,
        origin: InkSelectionOrigin,
        createdAtMs: Long,
    ): InkSelectionContext? {
        InkLimits.requireValid(document)
        val selectedIds = document.strokes.filter { InkGeometry.intersects(it, bounds) }.map { it.strokeId }
        if (selectedIds.isEmpty()) return null
        return InkSelectionContext(workspaceObjectId, pageId, bounds, selectedIds, sourceRevision,
            origin, createdAtMs, createdAtMs)
    }

    /** Returns null when the caller's source identity, revision, or selected strokes have changed. */
    fun resolve(
        document: InkDocument,
        context: InkSelectionContext,
        workspaceObjectId: String,
        pageId: String?,
        sourceRevision: String,
    ): List<InkStroke>? {
        InkLimits.requireValid(document)
        if (context.workspaceObjectId != workspaceObjectId || context.pageId != pageId ||
            context.sourceRevision != sourceRevision) return null
        val byId = document.strokes.associateBy { it.strokeId }
        val selected = context.selectedStrokeIds.map { byId[it] ?: return null }
        if (selected.any { !InkGeometry.intersects(it, context.bounds) }) return null
        return selected
    }
}
