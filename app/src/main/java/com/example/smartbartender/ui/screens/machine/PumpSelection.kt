package com.example.smartbartender.ui.screens.machine

import com.example.smartbartender.domain.model.BottleCatalog
import com.example.smartbartender.domain.model.MachineSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One pump on a screen that drives pumps directly — calibration and cleaning. */
data class PumpRow(
    val pump: Int,
    /** The bottle the machine has on this pump, or null for an empty slot. */
    val bottleName: String?,
    val mlPerSecond: Double,
    val selected: Boolean,
)

/**
 * Which pumps a calibration or cleaning run will drive.
 *
 * Remembers the pumps the user *unticked* rather than the ones ticked, so a pump the machine
 * reports later starts out selected.
 */
class PumpSelection {
    private val deselected = MutableStateFlow<Set<Int>>(emptySet())

    val deselectedPumps: StateFlow<Set<Int>> = deselected.asStateFlow()

    fun toggle(pump: Int) = deselected.update { if (pump in it) it - pump else it + pump }
}

/** The machine's pumps as rows, ticked unless the user left them out. */
fun MachineSnapshot?.pumpRows(deselected: Set<Int>): List<PumpRow> =
    this?.slots.orEmpty().map { slot ->
        PumpRow(
            pump = slot.pump,
            bottleName = slot.bottleId?.let { id -> BottleCatalog.byId(id)?.displayName ?: id },
            mlPerSecond = slot.mlPerSecond,
            selected = slot.pump !in deselected,
        )
    }

/** The ticked pumps, or null — which the machine reads as "every pump" — when all are ticked. */
fun List<PumpRow>.selectedPumpsOrAll(): List<Int>? =
    filter { it.selected }.map { it.pump }.takeIf { it.size < size }
