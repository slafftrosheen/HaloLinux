package com.halo.sdk.host

/** Deterministic host clock state; adapters schedule advance(), never infer missing edges. */
class AccessoryGestureInterpreter(
    private val doubleWindowMs: Long = 350, private val longPressMs: Long = 650,
    private val debounceMs: Long = 40,
) {
    private data class Key(val endpoint: String, val epoch: String, val control: AccessoryControl)
    private data class Pending(var down: AccessoryControlEvent.Button? = null,
        var single: AccessoryControlEvent.Button? = null, var due: Long = 0,
        var lastEdge: Long = Long.MIN_VALUE, var lastAction: AccessoryControlAction? = null)
    private val pending = mutableMapOf<Key, Pending>()
    private val seen = linkedSetOf<String>()
    private val epochs = mutableMapOf<String, String>()

    fun connect(endpoint: String, epoch: String) { disconnect(endpoint); epochs[endpoint] = epoch }
    fun disconnect(endpoint: String) {
        pending.keys.removeAll { it.endpoint == endpoint }; epochs.remove(endpoint)
        seen.removeAll { it.startsWith("$endpoint|") }
    }
    fun accept(event: AccessoryControlEvent.Button, now: Long, doubleMapped: Boolean): List<AccessoryControlEvent.Button> {
        val d = event.delivery ?: return emptyList()
        if (epochs[d.endpointId] != d.epoch || event.timestamp > now + 100 || now - event.timestamp > 2_000 ||
            d.evidence !in setOf(ControlEvidence.VERIFIED_PLATFORM, ControlEvidence.VERIFIED_VENDOR)) return emptyList()
        val token = "${d.endpointId}|${d.epoch}|${d.eventId}|${event.control}|${event.action}"
        if (!seen.add(token)) return emptyList()
        if (seen.size > 512) seen.remove(seen.first())
        if (d.classified) return listOf(event)
        if (!d.reliableEdges || event.action !in setOf(AccessoryControlAction.PRESS, AccessoryControlAction.RELEASE)) return emptyList()
        val state = pending.getOrPut(Key(d.endpointId, d.epoch, event.control)) { Pending() }
        if (state.lastAction == event.action || (state.lastEdge != Long.MIN_VALUE && event.timestamp - state.lastEdge < debounceMs)) return emptyList()
        state.lastEdge = event.timestamp; state.lastAction = event.action
        if (event.action == AccessoryControlAction.PRESS) {
            val expired = state.single?.takeIf { event.timestamp > state.due }
            if (expired != null) state.single = null
            state.down = event
            return listOfNotNull(expired?.copy(action = AccessoryControlAction.SINGLE))
        }
        val down = state.down ?: return emptyList()
        state.down = null
        if (event.timestamp - down.timestamp >= longPressMs) {
            state.single = null
            return listOf(event.copy(action = AccessoryControlAction.LONG))
        }
        val first = state.single
        if (first != null && event.timestamp <= state.due) {
            state.single = null
            return listOf(event.copy(action = AccessoryControlAction.DOUBLE))
        }
        val expired = first?.copy(action = AccessoryControlAction.SINGLE)
        state.single = null
        if (!doubleMapped) return listOfNotNull(expired, event.copy(action = AccessoryControlAction.SINGLE))
        state.single = event; state.due = event.timestamp + doubleWindowMs
        return listOfNotNull(expired)
    }
    fun advance(now: Long): List<AccessoryControlEvent.Button> = pending.values.mapNotNull { state ->
        // A second hold may still resolve long; don't emit its preceding tap prematurely.
        if (state.down != null || state.single == null || now < state.due) null
        else state.single!!.copy(action = AccessoryControlAction.SINGLE).also { state.single = null }
    }
}
