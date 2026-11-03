package nl.wdhertog.library.data.repository

import nl.wdhertog.library.data.db.SeriesSummary
import nl.wdhertog.library.util.TextNormalizer

/** Twee series die waarschijnlijk dezelfde zijn; [keep] heeft de meeste delen en blijft bij samenvoegen over. */
data class SeriesDuplicate(val keep: SeriesSummary, val other: SeriesSummary) {
    /** Blijft gelijk, ook als de volgorde in de lijst verandert; om "negeren" te onthouden. */
    val key: String get() = listOf(keep.id, other.id).sorted().joinToString("-")
}

/**
 * Herkent series die dubbel zijn ontstaan, bijvoorbeeld uit verschillende bronnen ("Arsène Lupin" en
 * "Arsène Lupin literary series"). Voorzichtig: alleen als de namen gelijk zijn zonder woorden als
 * "serie" of "trilogy", of als de ene naam (van minstens twee woorden) het begin is van de andere.
 * Dezelfde serie in een andere taal ("De Grijze Jager", "Ranger's Apprentice") herkent dit niet.
 */
object SeriesDuplicates {
    private val noise = setOf("serie", "series", "reeks", "trilogy", "trilogie", "literary", "saga", "cycle", "cyclus", "boeken", "books")
    private val nonWord = Regex("[^a-z0-9]+")

    fun find(series: List<SeriesSummary>, ignored: Set<String> = emptySet()): List<SeriesDuplicate> {
        val keyed = series.map { it to words(it.name) }.filter { it.second.isNotEmpty() }
        val result = mutableListOf<SeriesDuplicate>()
        val used = mutableSetOf<Long>()
        for (i in keyed.indices) for (j in i + 1 until keyed.size) {
            val (a, wa) = keyed[i]
            val (b, wb) = keyed[j]
            if (a.id in used || b.id in used || !similar(wa, wb)) continue
            val (keep, other) = if (b.works > a.works || (b.works == a.works && b.id < a.id)) b to a else a to b
            val duplicate = SeriesDuplicate(keep, other)
            if (duplicate.key in ignored) continue
            result += duplicate
            used += a.id
            used += b.id
        }
        return result
    }

    private fun words(name: String) = TextNormalizer.titleSortKey(name).split(nonWord).filter { it.isNotEmpty() && it !in noise }

    private fun similar(a: List<String>, b: List<String>): Boolean {
        if (a == b) return true
        val (short, long) = if (a.size <= b.size) a to b else b to a
        return short.size >= 2 && long.take(short.size) == short
    }
}
