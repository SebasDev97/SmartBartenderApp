package com.example.smartbartender.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.smartbartender.R
import com.example.smartbartender.domain.model.AlcoholContent
import com.example.smartbartender.domain.model.BottleCategory
import com.example.smartbartender.domain.model.MachineBackend

/*
 * The words for the domain's enums. The domain names things; the UI decides how to say them.
 */

@Composable
fun BottleCategory.label(): String = stringResource(
    when (this) {
        BottleCategory.SPIRIT -> R.string.category_spirits
        BottleCategory.LIQUEUR -> R.string.category_liqueurs
        BottleCategory.JUICE -> R.string.category_juices
        BottleCategory.MIXER -> R.string.category_mixers
        BottleCategory.DAIRY -> R.string.category_dairy
    },
)

@Composable
fun AlcoholContent.label(): String = stringResource(
    when (this) {
        AlcoholContent.ALCOHOLIC -> R.string.alcohol_alcoholic
        AlcoholContent.NON_ALCOHOLIC -> R.string.alcohol_non_alcoholic
        AlcoholContent.OPTIONAL -> R.string.alcohol_optional
    },
)

@Composable
fun MachineBackend.label(): String = stringResource(
    when (this) {
        MachineBackend.SIMULATED -> R.string.backend_simulated
        MachineBackend.ARDUINO -> R.string.backend_hardware
        MachineBackend.UNKNOWN -> R.string.backend_unknown
    },
)
