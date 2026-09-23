package com.example.smartbartender.domain.model

/**
 * Which bottle sits in which physical slot.
 *
 * [BottleCatalog.inSlotOrder] answers "in what order does the catalog list these bottles",
 * which is not the same question: it reshuffles the rack whenever a bottle is ejected. That
 * was harmless while pours were an animation. Now that slot N is a real pump with real
 * liquid in it, the assignment has to be stable *and* survive a restart, or the machine
 * pours gin when the app thinks it asked for vodka.
 */
object SlotRack {

    private const val SEPARATOR = '|'

    val empty: List<String?> = List(BottleCatalog.MAX_SLOTS) { null }

    /**
     * Keeps every bottle in the slot it was placed in, and drops bottles no longer loaded.
     * Anything loaded but unplaced takes the first free slot, in catalog order.
     *
     * Ejecting the bottle in slot 1 therefore leaves the rest where they are, instead of
     * shuffling them all up one — which is what the bottles screen, drawn as the machine's
     * four physical slots, promises.
     */
    fun reconcile(assignment: List<String?>, loaded: Set<String>): List<String?> {
        val slots = assignment.pad().map { id -> id?.takeIf { it in loaded } }.toMutableList()
        BottleCatalog.inSlotOrder(loaded)
            .filterNot { bottle -> bottle.id in slots }
            .forEach { bottle ->
                val free = slots.indexOf(null)
                if (free >= 0) slots[free] = bottle.id
            }
        return slots
    }

    /** `"vodka|cola||lime_juice"` — position is the slot, an empty segment is an empty slot. */
    fun encode(slots: List<String?>): String =
        slots.pad().joinToString(SEPARATOR.toString()) { it.orEmpty() }

    fun parse(stored: String): List<String?> {
        if (stored.isBlank()) return empty
        return stored.split(SEPARATOR)
            .map { segment -> segment.takeIf { it.isNotBlank() && BottleCatalog.byId(it) != null } }
            .pad()
    }

    /** Slot number (1-based, as the machine and the API count them) for a loaded bottle. */
    fun pumpFor(slots: List<String?>, bottleId: String): Int? =
        slots.indexOf(bottleId).takeIf { it >= 0 }?.plus(1)

    /** Truncate or pad to the machine's real slot count, whatever was stored. */
    private fun List<String?>.pad(): List<String?> =
        List(BottleCatalog.MAX_SLOTS) { index -> getOrNull(index) }
}
