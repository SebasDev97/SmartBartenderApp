package com.example.smartbartender.data.remote.dto

import com.example.smartbartender.domain.model.Cocktail
import com.example.smartbartender.domain.model.CocktailSummary
import com.example.smartbartender.domain.model.RecipeIngredient
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder

/**
 * TheCocktailDB answers "no results" inconsistently: sometimes `"drinks": null`, sometimes
 * the *string* `"drinks": "None Found"`. This serializer accepts either and yields an empty
 * list, so a miss never surfaces as a parse error.
 */
private open class LenientDrinkListSerializer<T>(
    private val itemSerializer: KSerializer<T>,
) : KSerializer<List<T>> {
    private val delegate = ListSerializer(itemSerializer)
    override val descriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<T> {
        val jsonDecoder = decoder as? JsonDecoder ?: return delegate.deserialize(decoder)
        val element = jsonDecoder.decodeJsonElement()
        if (element !is JsonArray) return emptyList()
        return jsonDecoder.json.decodeFromJsonElement(delegate, element)
    }

    override fun serialize(encoder: Encoder, value: List<T>) = delegate.serialize(encoder, value)
}

private object CocktailListSerializer : LenientDrinkListSerializer<CocktailDto>(CocktailDto.serializer())
private object SummaryListSerializer : LenientDrinkListSerializer<DrinkSummaryDto>(DrinkSummaryDto.serializer())

@Serializable
data class CocktailResponse(
    @Serializable(with = CocktailListSerializer::class)
    val drinks: List<CocktailDto> = emptyList(),
)

@Serializable
data class DrinkSummaryResponse(
    @Serializable(with = SummaryListSerializer::class)
    val drinks: List<DrinkSummaryDto> = emptyList(),
)

@Serializable
data class DrinkSummaryDto(
    @SerialName("idDrink") val id: String,
    @SerialName("strDrink") val name: String? = null,
    @SerialName("strDrinkThumb") val thumb: String? = null,
) {
    fun toDomain() = CocktailSummary(id = id, name = name.orEmpty(), thumbUrl = thumb)
}

@Serializable
data class CocktailDto(
    @SerialName("idDrink") val id: String,
    @SerialName("strDrink") val name: String? = null,
    @SerialName("strDrinkThumb") val thumb: String? = null,
    @SerialName("strCategory") val category: String? = null,
    @SerialName("strAlcoholic") val alcoholic: String? = null,
    @SerialName("strGlass") val glass: String? = null,
    @SerialName("strInstructions") val instructions: String? = null,
    @SerialName("strIngredient1") val ingredient1: String? = null,
    @SerialName("strIngredient2") val ingredient2: String? = null,
    @SerialName("strIngredient3") val ingredient3: String? = null,
    @SerialName("strIngredient4") val ingredient4: String? = null,
    @SerialName("strIngredient5") val ingredient5: String? = null,
    @SerialName("strIngredient6") val ingredient6: String? = null,
    @SerialName("strIngredient7") val ingredient7: String? = null,
    @SerialName("strIngredient8") val ingredient8: String? = null,
    @SerialName("strIngredient9") val ingredient9: String? = null,
    @SerialName("strIngredient10") val ingredient10: String? = null,
    @SerialName("strIngredient11") val ingredient11: String? = null,
    @SerialName("strIngredient12") val ingredient12: String? = null,
    @SerialName("strIngredient13") val ingredient13: String? = null,
    @SerialName("strIngredient14") val ingredient14: String? = null,
    @SerialName("strIngredient15") val ingredient15: String? = null,
    @SerialName("strMeasure1") val measure1: String? = null,
    @SerialName("strMeasure2") val measure2: String? = null,
    @SerialName("strMeasure3") val measure3: String? = null,
    @SerialName("strMeasure4") val measure4: String? = null,
    @SerialName("strMeasure5") val measure5: String? = null,
    @SerialName("strMeasure6") val measure6: String? = null,
    @SerialName("strMeasure7") val measure7: String? = null,
    @SerialName("strMeasure8") val measure8: String? = null,
    @SerialName("strMeasure9") val measure9: String? = null,
    @SerialName("strMeasure10") val measure10: String? = null,
    @SerialName("strMeasure11") val measure11: String? = null,
    @SerialName("strMeasure12") val measure12: String? = null,
    @SerialName("strMeasure13") val measure13: String? = null,
    @SerialName("strMeasure14") val measure14: String? = null,
    @SerialName("strMeasure15") val measure15: String? = null,
) {
    /** Pairs strIngredientN with strMeasureN, dropping the empty tail slots. */
    private fun ingredientPairs(): List<RecipeIngredient> {
        val names = listOf(
            ingredient1, ingredient2, ingredient3, ingredient4, ingredient5,
            ingredient6, ingredient7, ingredient8, ingredient9, ingredient10,
            ingredient11, ingredient12, ingredient13, ingredient14, ingredient15,
        )
        val measures = listOf(
            measure1, measure2, measure3, measure4, measure5,
            measure6, measure7, measure8, measure9, measure10,
            measure11, measure12, measure13, measure14, measure15,
        )
        return names.mapIndexedNotNull { index, rawName ->
            val ingredientName = rawName?.trim().orEmpty()
            if (ingredientName.isEmpty()) return@mapIndexedNotNull null
            RecipeIngredient(
                name = ingredientName,
                measure = measures[index]?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }

    fun toDomain() = Cocktail(
        id = id,
        name = name?.trim().orEmpty(),
        thumbUrl = thumb,
        category = category?.trim()?.takeIf { it.isNotEmpty() },
        alcoholic = alcoholic?.trim()?.takeIf { it.isNotEmpty() },
        glass = glass?.trim()?.takeIf { it.isNotEmpty() },
        instructions = instructions?.trim()?.takeIf { it.isNotEmpty() },
        ingredients = ingredientPairs(),
    )
}
