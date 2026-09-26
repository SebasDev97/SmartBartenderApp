package com.example.smartbartender.data.local

import com.example.smartbartender.domain.model.BottleCatalog
import com.example.smartbartender.domain.model.CocktailSummary
import com.example.smartbartender.domain.model.CustomDrink
import com.example.smartbartender.domain.model.CustomDrinks
import com.example.smartbartender.domain.model.CustomItem
import com.example.smartbartender.domain.model.PourOutcome
import com.example.smartbartender.domain.model.PourRecord
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val StoredJson = Json { ignoreUnknownKeys = true }

/**
 * A list persisted as one JSON string of [E] entries.
 *
 * Stored data outlives the build that wrote it, so reading never throws: a blank or
 * unreadable value decodes to an empty list, and an entry [fromEntry] rejects is dropped.
 */
internal class JsonListCodec<E, M>(
    entrySerializer: KSerializer<E>,
    private val toEntry: (M) -> E,
    private val fromEntry: (E) -> M?,
) {
    private val serializer = ListSerializer(entrySerializer)

    fun encode(items: List<M>): String = StoredJson.encodeToString(serializer, items.map(toEntry))

    fun decode(stored: String): List<M> {
        if (stored.isBlank()) return emptyList()
        return runCatching { StoredJson.decodeFromString(serializer, stored) }
            .getOrDefault(emptyList())
            .mapNotNull(fromEntry)
    }
}

/**
 * Favourites keep the name and thumbnail alongside the id, so the Favourites list draws
 * straight from storage instead of paying one `lookup.php?i=` per drink against the public
 * API — and still shows something when that API is unreachable.
 */
internal object FavouritesCodec {

    @Serializable
    private data class Entry(val id: String, val name: String, val thumbUrl: String? = null)

    private val codec = JsonListCodec(
        Entry.serializer(),
        toEntry = { Entry(it.id, it.name, it.thumbUrl) },
        fromEntry = { entry -> CocktailSummary(entry.id, entry.name, entry.thumbUrl).takeIf { entry.id.isNotBlank() } },
    )

    fun encode(favourites: List<CocktailSummary>): String = codec.encode(favourites)

    fun decode(stored: String): List<CocktailSummary> = codec.decode(stored).distinctBy { it.id }
}

/** Items whose bottle has left the catalog are dropped, and so is a drink left with nothing to pour. */
internal object CustomDrinksCodec {

    @Serializable
    private data class Entry(
        val id: String,
        val name: String,
        val emoji: String = "",
        val colorArgb: Long = 0,
        val notes: String = "",
        val items: List<ItemEntry> = emptyList(),
    )

    @Serializable
    private data class ItemEntry(val bottleId: String, val ml: Int)

    private val codec = JsonListCodec(
        Entry.serializer(),
        toEntry = { drink ->
            Entry(
                id = drink.id,
                name = drink.name,
                emoji = drink.emoji,
                colorArgb = drink.colorArgb,
                notes = drink.notes,
                items = drink.items.map { ItemEntry(it.bottleId, it.ml) },
            )
        },
        fromEntry = ::toDrink,
    )

    fun encode(drinks: List<CustomDrink>): String = codec.encode(drinks)

    fun decode(stored: String): List<CustomDrink> = codec.decode(stored).distinctBy { it.id }

    private fun toDrink(entry: Entry): CustomDrink? {
        if (!CustomDrinks.isCustomId(entry.id)) return null
        val items = entry.items
            .filter { BottleCatalog.byId(it.bottleId) != null }
            .map { CustomItem(it.bottleId, it.ml) }
        if (items.isEmpty()) return null
        return CustomDrink(entry.id, entry.name, entry.emoji, entry.colorArgb, entry.notes, items)
    }
}

internal object PourHistoryCodec {

    @Serializable
    private data class Entry(
        val jobId: String,
        val drinkId: String? = null,
        val drinkName: String = "",
        val outcome: String = "",
        val finishedAtMs: Long = 0,
        val ml: Map<String, Double> = emptyMap(),
    )

    private val codec = JsonListCodec(
        Entry.serializer(),
        toEntry = { Entry(it.jobId, it.drinkId, it.drinkName, it.outcome.name, it.finishedAtMs, it.mlByBottle) },
        fromEntry = ::toRecord,
    )

    fun encode(records: List<PourRecord>): String = codec.encode(records)

    fun decode(stored: String): List<PourRecord> = codec.decode(stored).distinctBy { it.jobId }

    private fun toRecord(entry: Entry): PourRecord? {
        val outcome = PourOutcome.entries.firstOrNull { it.name == entry.outcome }
        if (entry.jobId.isBlank() || outcome == null) return null
        return PourRecord(entry.jobId, entry.drinkId, entry.drinkName, outcome, entry.finishedAtMs, entry.ml)
    }
}
