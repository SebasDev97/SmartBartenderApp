package com.example.smartbartender.ui.screens.custom

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.smartbartender.R
import com.example.smartbartender.domain.model.BottleCatalog
import com.example.smartbartender.domain.model.DrinkProblem
import com.example.smartbartender.ui.components.CocktailImage
import com.example.smartbartender.ui.components.EmptyState
import com.example.smartbartender.ui.components.GlassPanel
import com.example.smartbartender.ui.components.LedState
import com.example.smartbartender.ui.components.MetaChip
import com.example.smartbartender.ui.components.SectionHeader
import com.example.smartbartender.ui.components.label
import com.example.smartbartender.ui.theme.ErrorRed
import com.example.smartbartender.ui.theme.NeonAmber
import com.example.smartbartender.ui.theme.NeonCyan
import com.example.smartbartender.ui.theme.Obsidian
import com.example.smartbartender.ui.theme.SteelOutline
import com.example.smartbartender.ui.theme.TextSecondary

/** Every callback the editor raises, bundled so the screen signature stays readable. */
data class CustomDrinkEditorActions(
    val onNameChange: (String) -> Unit,
    val onEmojiChange: (String) -> Unit,
    val onColourChange: (Long) -> Unit,
    val onNotesChange: (String) -> Unit,
    val onAddItem: () -> Unit,
    val onRemoveItem: (key: Int) -> Unit,
    val onMoveItem: (key: Int, offset: Int) -> Unit,
    val onMlTextChange: (key: Int, text: String) -> Unit,
    val onStepMl: (key: Int, delta: Int) -> Unit,
    val onOpenBottlePicker: (key: Int) -> Unit,
    val onDismissBottlePicker: () -> Unit,
    val onBottlePicked: (bottleId: String) -> Unit,
    val onSave: () -> Unit,
    val onDeleteRequest: () -> Unit,
    val onDeleteConfirm: () -> Unit,
    val onDeleteDismiss: () -> Unit,
)

/** Build or change a drink of your own: a name, a look, and a bottle per slot with exact ml. */
@Composable
fun CustomDrinkEditorScreen(
    state: CustomDrinkEditorUiState,
    led: LedState,
    actions: CustomDrinkEditorActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val accent = led.primary

    when {
        state.isLoading -> Box(modifier.fillMaxSize())
        state.notFound -> EmptyState(
            icon = Icons.Outlined.SearchOff,
            title = stringResource(R.string.editor_not_found),
            message = stringResource(R.string.error_drink_deleted),
            modifier = modifier.padding(contentPadding),
        )
        else -> EditorForm(state, accent, actions, modifier, contentPadding)
    }

    if (state.pickingItemKey != null) {
        BottlePickerSheet(
            loadedBottleIds = state.loadedBottleIds,
            // A bottle already on another row can't be picked twice; the row's own bottle can.
            takenBottleIds = state.usedBottleIds -
                state.items.firstOrNull { it.key == state.pickingItemKey }?.bottleId.orEmpty(),
            onPick = actions.onBottlePicked,
            onDismiss = actions.onDismissBottlePicker,
        )
    }

    if (state.showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = actions.onDeleteDismiss,
            title = {
                Text(
                    if (state.name.isBlank()) {
                        stringResource(R.string.editor_delete_title_unnamed)
                    } else {
                        stringResource(R.string.editor_delete_title, state.name)
                    },
                )
            },
            text = { Text(stringResource(R.string.editor_delete_message)) },
            confirmButton = {
                TextButton(onClick = actions.onDeleteConfirm) { Text(stringResource(R.string.editor_delete), color = ErrorRed) }
            },
            dismissButton = {
                TextButton(onClick = actions.onDeleteDismiss) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun EditorForm(
    state: CustomDrinkEditorUiState,
    accent: Color,
    actions: CustomDrinkEditorActions,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val drinkColour = Color(state.colorArgb)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 32.dp,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CocktailImage(
                url = null,
                contentDescription = null,
                look = state.draft.look,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(18.dp)),
            )
            Spacer(Modifier.width(14.dp))
            OutlinedTextField(
                value = state.name,
                onValueChange = actions.onNameChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.editor_name)) },
                placeholder = { Text(stringResource(R.string.editor_name_placeholder), color = TextSecondary) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Done,
                ),
                shape = MaterialTheme.shapes.large,
                colors = fieldColours(accent),
            )
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader(title = stringResource(R.string.editor_look), accent = accent)
        Spacer(Modifier.height(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DrinkEmojis.forEach { emoji ->
                val selected = emoji == state.emoji
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) drinkColour.copy(alpha = 0.22f) else Color.Transparent)
                        .border(1.dp, if (selected) drinkColour else SteelOutline, RoundedCornerShape(12.dp))
                        .clickable { actions.onEmojiChange(emoji) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(emoji, fontSize = 22.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DrinkColours.forEach { argb ->
                val selected = argb == state.colorArgb
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(if (selected) 3.dp else 0.dp, Color.White, CircleShape)
                        .clickable { actions.onColourChange(argb) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.editor_selected), tint = Obsidian)
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionHeader(
            title = stringResource(R.string.detail_ingredients),
            trailing = stringResource(R.string.editor_items_count, state.items.size, BottleCatalog.MAX_SLOTS),
            accent = accent,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.editor_pour_order),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            state.items.forEachIndexed { index, item ->
                IngredientEditorRow(
                    index = index,
                    item = item,
                    isFirst = index == 0,
                    isLast = index == state.items.lastIndex,
                    isLoaded = item.bottleId == null || item.bottleId in state.loadedBottleIds,
                    accent = accent,
                    actions = actions,
                )
            }
        }
        if (state.canAddItem) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = actions.onAddItem,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = accent)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.editor_add_ingredient), color = accent)
            }
        }

        Spacer(Modifier.height(16.dp))
        TotalReadout(totalMl = state.totalMl, maxPourMl = state.maxPourMl, accent = accent)

        Spacer(Modifier.height(24.dp))
        SectionHeader(title = stringResource(R.string.editor_notes), accent = accent)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.notes,
            onValueChange = actions.onNotesChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            placeholder = { Text(stringResource(R.string.editor_notes_placeholder), color = TextSecondary) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = MaterialTheme.shapes.large,
            colors = fieldColours(accent),
        )

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = actions.onSave,
            enabled = state.canSave,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = accent,
                contentColor = Obsidian,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Text(
                text = stringResource(if (state.isNew) R.string.editor_save_new else R.string.editor_save_changes),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        if (state.problems.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = state.problems.map { it.text() }.joinToString("\n"),
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (!state.isNew) {
            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = actions.onDeleteRequest,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = ErrorRed)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.editor_delete_drink), color = ErrorRed)
            }
        }
    }
}

@Composable
private fun IngredientEditorRow(
    index: Int,
    item: EditorItem,
    isFirst: Boolean,
    isLast: Boolean,
    isLoaded: Boolean,
    accent: Color,
    actions: CustomDrinkEditorActions,
) {
    val bottle = item.bottleId?.let(BottleCatalog::byId)
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        accent = if (isLoaded) null else NeonAmber,
        contentPadding = PaddingValues(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                    modifier = Modifier.width(20.dp),
                )
                TextButton(
                    onClick = { actions.onOpenBottlePicker(item.key) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = bottle?.displayName ?: stringResource(R.string.editor_choose_bottle),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (bottle == null) TextSecondary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (!isLoaded) MetaChip(text = stringResource(R.string.editor_not_loaded), accent = NeonAmber)
                IconButton(onClick = { actions.onRemoveItem(item.key) }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.editor_remove_ingredient), tint = TextSecondary)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(20.dp))
                IconButton(onClick = { actions.onStepMl(item.key, -ML_STEP) }) {
                    Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.editor_ml_less, ML_STEP), tint = accent)
                }
                OutlinedTextField(
                    value = item.mlText,
                    onValueChange = { actions.onMlTextChange(item.key, it) },
                    modifier = Modifier.width(96.dp),
                    singleLine = true,
                    suffix = { Text(stringResource(R.string.unit_ml), color = TextSecondary) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    shape = RoundedCornerShape(12.dp),
                    colors = fieldColours(accent),
                )
                IconButton(onClick = { actions.onStepMl(item.key, ML_STEP) }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.editor_ml_more, ML_STEP), tint = accent)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { actions.onMoveItem(item.key, -1) }, enabled = !isFirst) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.editor_pour_earlier))
                }
                IconButton(onClick = { actions.onMoveItem(item.key, 1) }, enabled = !isLast) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.editor_pour_later))
                }
            }
        }
    }
}

@Composable
private fun TotalReadout(totalMl: Int, maxPourMl: Double, accent: Color) {
    val over = totalMl > maxPourMl
    val colour = if (over) ErrorRed else accent
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.editor_total), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.pour_step_volume, totalMl, maxPourMl.toInt()),
                style = MaterialTheme.typography.titleMedium,
                color = colour,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { (totalMl / maxPourMl).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = colour,
            trackColor = SteelOutline,
        )
    }
}

/** Every catalog bottle, grouped like the Bottles tab, with the ones in the rack marked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BottlePickerSheet(
    loadedBottleIds: Set<String>,
    takenBottleIds: Set<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            BottleCatalog.byCategory.forEach { (category, bottles) ->
                item(key = category.name) {
                    SectionHeader(
                        title = category.label(),
                        accent = NeonCyan,
                        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                    )
                }
                items(bottles, key = { it.id }) { bottle ->
                    val taken = bottle.id in takenBottleIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !taken) { onPick(bottle.id) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = bottle.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (taken) TextSecondary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        when {
                            taken -> Text(
                                stringResource(R.string.editor_in_use),
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                            )
                            bottle.id in loadedBottleIds -> MetaChip(text = stringResource(R.string.editor_loaded), accent = NeonCyan)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrinkProblem.text(): String = when (this) {
    DrinkProblem.NoName -> stringResource(R.string.problem_no_name)
    DrinkProblem.NoItems -> stringResource(R.string.problem_no_items)
    is DrinkProblem.TooManyItems -> stringResource(R.string.problem_too_many_items, maxItems)
    DrinkProblem.BottleMissing -> stringResource(R.string.problem_bottle_missing)
    DrinkProblem.BottleRepeated -> stringResource(R.string.problem_bottle_repeated)
    is DrinkProblem.VolumeOutOfRange -> stringResource(R.string.problem_volume_range, minMl, maxMl)
    is DrinkProblem.OverGlass -> stringResource(R.string.problem_over_glass, totalMl, glassMl)
}

@Composable
private fun fieldColours(accent: Color) = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = accent,
    unfocusedBorderColor = SteelOutline,
    focusedLabelColor = accent,
    cursorColor = accent,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
)

private const val ML_STEP = 5
