package com.example.smartbartender.domain.model

import java.text.Normalizer

private val COMBINING_MARKS = Regex("\\p{Mn}+")
private val NOT_ALPHANUMERIC = Regex("[^a-z0-9 ]")
private val WHITESPACE = Regex("\\s+")

/**
 * Case-, accent- and punctuation-insensitive form of a name, used for every ingredient
 * comparison and every name search. Diacritics are folded first, so "Curaçao" and "Curacao"
 * are the same liquid, and "PINA" finds "Piña Colada".
 */
fun String.folded(): String =
    Normalizer.normalize(trim().lowercase(), Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .replace(NOT_ALPHANUMERIC, " ")
        .replace(WHITESPACE, " ")
        .trim()

/** The items whose [name] contains [query], compared [folded]. A blank query keeps them all. */
fun <T> List<T>.filterByName(query: String, name: (T) -> String): List<T> {
    val needle = query.folded()
    if (needle.isEmpty()) return this
    return filter { needle in name(it).folded() }
}
