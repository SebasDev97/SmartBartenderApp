package com.example.smartbartender.ui.screens.custom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import com.example.smartbartender.data.hardware.BartenderMachine
import com.example.smartbartender.data.local.CustomDrinkStore
import com.example.smartbartender.data.local.RackStore
import com.example.smartbartender.di.appContainer
import com.example.smartbartender.domain.model.BottleCatalog
import com.example.smartbartender.domain.model.CustomDrink
import com.example.smartbartender.domain.model.CustomDrinks
import com.example.smartbartender.domain.model.CustomItem
import com.example.smartbartender.domain.model.DEFAULT_MAX_POUR_ML
import com.example.smartbartender.domain.model.DrinkProblem
import com.example.smartbartender.domain.model.MAX_ITEM_ML
import com.example.smartbartender.ui.navigation.CustomEditRoute
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One ingredient row while it is being edited. [key] keeps a row's identity stable while
 * rows are reordered, and [mlText] is the raw field text, so the field may be empty mid-edit.
 */
data class EditorItem(
    val key: Int,
    val bottleId: String? = null,
    val mlText: String = DEFAULT_ITEM_ML.toString(),
) {
    val ml: Int get() = mlText.toIntOrNull() ?: 0
}

data class CustomDrinkEditorUiState(
    val isLoading: Boolean = false,
    val isNew: Boolean = true,
    /** Editing a drink that no longer exists, e.g. deleted from another screen. */
    val notFound: Boolean = false,
    val name: String = "",
    val emoji: String = DrinkEmojis.first(),
    val colorArgb: Long = DrinkColours.first(),
    val notes: String = "",
    val items: List<EditorItem> = listOf(EditorItem(key = 0)),
    val loadedBottleIds: Set<String> = emptySet(),
    val maxPourMl: Double = DEFAULT_MAX_POUR_ML,
    /** The row whose bottle picker is open, if any. */
    val pickingItemKey: Int? = null,
    val showDeleteConfirm: Boolean = false,
    val isSaving: Boolean = false,
) {
    /** What would be saved right now. Rows with no bottle yet fail validation as such. */
    val draft: CustomDrink = CustomDrink(
        id = "",
        name = name.trim(),
        emoji = emoji,
        colorArgb = colorArgb,
        notes = notes.trim(),
        items = items.map { CustomItem(it.bottleId.orEmpty(), it.ml) },
    )

    val totalMl: Int get() = draft.totalMl

    val problems: List<DrinkProblem> = CustomDrinks.validate(draft, maxPourMl)

    val canSave: Boolean get() = problems.isEmpty() && !isSaving

    val canAddItem: Boolean get() = items.size < BottleCatalog.MAX_SLOTS

    val usedBottleIds: Set<String> = items.mapNotNullTo(HashSet()) { it.bottleId }
}

/** What the editor asks its host to do once it is done. */
sealed interface EditorEvent {
    /** [id] is the drink's, new or not; [wasNew] tells the host whether to open it or just go back. */
    data class Saved(val id: String, val wasNew: Boolean) : EditorEvent

    data object Deleted : EditorEvent
}

/** Creates a new custom drink, or edits the one whose id arrived as a navigation argument. */
class CustomDrinkEditorViewModel(
    private val drinkId: String?,
    private val customDrinks: CustomDrinkStore,
    rack: RackStore,
    machine: BartenderMachine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CustomDrinkEditorUiState(isLoading = drinkId != null, isNew = drinkId == null))
    val uiState = _uiState.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events: Flow<EditorEvent> = _events.receiveAsFlow()

    private var nextKey = 1

    init {
        if (drinkId != null) load(drinkId)
        viewModelScope.launch {
            rack.loadedBottleIds.collect { ids -> _uiState.update { it.copy(loadedBottleIds = ids) } }
        }
        viewModelScope.launch {
            machine.connection.collect { connection ->
                val maxPourMl = connection.snapshotOrNull?.maxPourMl ?: DEFAULT_MAX_POUR_ML
                _uiState.update { it.copy(maxPourMl = maxPourMl) }
            }
        }
    }

    /** Read once: the form is the user's to change from here, not a mirror of storage. */
    private fun load(id: String) {
        viewModelScope.launch {
            val drink = customDrinks.customDrinks.first().firstOrNull { it.id == id }
            if (drink == null) {
                _uiState.update { it.copy(isLoading = false, notFound = true) }
                return@launch
            }
            _uiState.update {
                it.copy(
                    isLoading = false,
                    name = drink.name,
                    emoji = drink.emoji,
                    colorArgb = drink.colorArgb,
                    notes = drink.notes,
                    items = drink.items.map { item ->
                        EditorItem(key = nextKey++, bottleId = item.bottleId, mlText = item.ml.toString())
                    },
                )
            }
        }
    }

    fun onNameChange(name: String) = _uiState.update { it.copy(name = name.take(MAX_NAME_LENGTH)) }

    fun onEmojiChange(emoji: String) = _uiState.update { it.copy(emoji = emoji) }

    fun onColourChange(colorArgb: Long) = _uiState.update { it.copy(colorArgb = colorArgb) }

    fun onNotesChange(notes: String) = _uiState.update { it.copy(notes = notes) }

    fun addItem() = _uiState.update {
        if (!it.canAddItem) it else it.copy(items = it.items + EditorItem(key = nextKey++), pickingItemKey = null)
    }

    fun removeItem(key: Int) = _uiState.update { state ->
        state.copy(items = state.items.filterNot { it.key == key })
    }

    /** Moves a row up ([offset] -1) or down (+1). Row order is the order the pumps run in. */
    fun moveItem(key: Int, offset: Int) = _uiState.update { state ->
        val from = state.items.indexOfFirst { it.key == key }
        val to = from + offset
        if (from < 0 || to !in state.items.indices) return@update state
        val items = state.items.toMutableList()
        items.add(to, items.removeAt(from))
        state.copy(items = items)
    }

    /** Keeps digits only, and at most three of them: nothing a single pump pours needs more. */
    fun onMlTextChange(key: Int, text: String) = updateItem(key) {
        it.copy(mlText = text.filter(Char::isDigit).take(3))
    }

    fun stepMl(key: Int, delta: Int) = updateItem(key) {
        val stepped = (it.ml + delta).coerceIn(CustomDrinks.MIN_ITEM_ML, MAX_ITEM_ML.toInt())
        it.copy(mlText = stepped.toString())
    }

    fun openBottlePicker(key: Int) = _uiState.update { it.copy(pickingItemKey = key) }

    fun dismissBottlePicker() = _uiState.update { it.copy(pickingItemKey = null) }

    fun onBottlePicked(bottleId: String) {
        val key = _uiState.value.pickingItemKey ?: return
        updateItem(key) { it.copy(bottleId = bottleId) }
        dismissBottlePicker()
    }

    private fun updateItem(key: Int, transform: (EditorItem) -> EditorItem) = _uiState.update { state ->
        state.copy(items = state.items.map { if (it.key == key) transform(it) else it })
    }

    fun save() {
        val state = _uiState.value
        if (!state.canSave) return
        val id = drinkId ?: CustomDrinks.newId()
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch {
            customDrinks.saveCustomDrink(state.draft.copy(id = id))
            _events.send(EditorEvent.Saved(id, wasNew = drinkId == null))
        }
    }

    fun requestDelete() = _uiState.update { it.copy(showDeleteConfirm = true) }

    fun dismissDelete() = _uiState.update { it.copy(showDeleteConfirm = false) }

    fun confirmDelete() {
        val id = drinkId ?: return
        _uiState.update { it.copy(showDeleteConfirm = false, isSaving = true) }
        viewModelScope.launch {
            customDrinks.deleteCustomDrink(id)
            _events.send(EditorEvent.Deleted)
        }
    }

    companion object {
        private const val MAX_NAME_LENGTH = 40

        /** The optional drink id arrives as the [CustomEditRoute] navigation argument. */
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = appContainer
                CustomDrinkEditorViewModel(
                    drinkId = createSavedStateHandle().toRoute<CustomEditRoute>().drinkId?.takeIf { it.isNotBlank() },
                    customDrinks = container.customDrinks,
                    rack = container.rack,
                    machine = container.machine,
                )
            }
        }
    }
}

/** A single shot's worth: a sensible starting amount for a new row. */
private const val DEFAULT_ITEM_ML = 40

/** The emoji a custom drink can wear instead of a photo. */
val DrinkEmojis = listOf("🍹", "🍸", "🥃", "🍷", "🍋", "🍊", "🍒", "🍓", "🥥", "🌴", "🧉", "🔥")

/** Preset accent colours as ARGB, matching the app's neon palette. */
val DrinkColours = listOf(
    0xFF2AF5E4, // cyan
    0xFFFF3DD8, // magenta
    0xFF8B5CFF, // violet
    0xFFFFB547, // amber
    0xFF9DFF3D, // lime
    0xFF3D8BFF, // blue
    0xFFFF5D6C, // red
)
