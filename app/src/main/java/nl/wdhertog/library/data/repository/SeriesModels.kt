package nl.wdhertog.library.data.repository

import nl.wdhertog.library.data.db.SeriesBookRow
import nl.wdhertog.library.data.db.SeriesPart

/** Eén deel van een serie: een werk met de exemplaren die je ervan hebt, of een ontbrekend nummer. */
sealed interface SeriesVolume {
    val index: String?

    data class Present(
        override val index: String?,
        val workId: Long,
        val title: String,
        val authors: String?,
        val books: List<SeriesBookRow>,
        val read: Boolean,
    ) : SeriesVolume

    /** Een deel dat je niet hebt; titel en jaar als Wikidata ze kent. */
    data class Missing(override val index: String, val title: String? = null, val year: Int? = null) : SeriesVolume
}

object SeriesOrder {

    /**
     * Hogere nummers zijn geen deelnummers maar bijv. jaartallen (Boekenweekgeschenk 2022);
     * daar heeft "ontbrekende delen" geen zin.
     */
    const val MAX_PLAUSIBLE_INDEX = 100

    private val romans = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100)

    /**
     * Sorteerwaarde van een deelnummer: "2" → 2, "0.5" en "2,5" → tussenin, "II" → 2.
     * Zonder bruikbaar nummer achteraan.
     */
    fun sortKey(index: String?): Double {
        val text = index?.trim()?.replace(',', '.') ?: return Double.MAX_VALUE
        text.toDoubleOrNull()?.let { return it }
        Regex("""^\d+(\.\d+)?""").find(text)?.value?.toDoubleOrNull()?.let { return it }
        roman(text.uppercase())?.let { return it.toDouble() }
        return Double.MAX_VALUE
    }

    private fun roman(text: String): Int? {
        if (text.isEmpty() || text.any { it !in romans }) return null
        var total = 0
        text.forEachIndexed { i, c ->
            val value = romans.getValue(c)
            val next = text.getOrNull(i + 1)?.let(romans::getValue) ?: 0
            total += if (value < next) -value else value
        }
        return total
    }

    /**
     * Het laatste deelnummer volgens Wikidata, maar alleen als Wikidata minstens de helft van de nummers
     * tot daar kent. Kent het alleen deel 13 van een serie, dan is 13 geen betrouwbaar totaal.
     */
    fun completeTotal(parts: List<SeriesPart>): Int? {
        val numbers = parts.mapNotNull { it.ordinal.trim().toIntOrNull() }.filter { it in 1..MAX_PLAUSIBLE_INDEX }.toSet()
        val highest = numbers.maxOrNull() ?: return null
        return highest.takeIf { numbers.size * 2 >= it }
    }

    /**
     * Delen op volgorde, met ontbrekende hele nummers tussen 1 en het hoogste nummer (of het opgegeven
     * aantal delen, of het laatste deel volgens Wikidata) als gat. [parts] geeft die gaten een titel,
     * en voegt delen met een tussennummer (bijv. 2.5) toe die je niet hebt.
     */
    fun volumes(rows: List<SeriesBookRow>, totalVolumes: Int?, parts: List<SeriesPart> = emptyList()): List<SeriesVolume> {
        val present = rows.groupBy { it.workId }.map { (workId, books) ->
            val first = books.first()
            SeriesVolume.Present(
                index = first.seriesIndex,
                workId = workId,
                title = first.workTitle.ifBlank { first.bookTitle },
                authors = first.authors,
                books = books,
                read = books.any { it.read },
            )
        }
        val numbers = present.map { sortKey(it.index) }.filter { it != Double.MAX_VALUE }
        val highestNumber = numbers.maxOrNull()?.toInt()?.takeIf { it <= MAX_PLAUSIBLE_INDEX } ?: 0
        val partsByNumber = parts.filter { sortKey(it.ordinal) != Double.MAX_VALUE }.associateBy { sortKey(it.ordinal) }
        val highestPart = completeTotal(parts) ?: 0
        val highest = maxOf(highestNumber, totalVolumes ?: 0, highestPart)
        val gaps = (1..highest).filter { n -> numbers.none { it == n.toDouble() } }.map { n ->
            val part = partsByNumber[n.toDouble()]
            SeriesVolume.Missing(n.toString(), part?.title?.ifBlank { null }, part?.year)
        }
        // Tussennummers (bijv. een novelle 2.5) die je niet hebt.
        val between = partsByNumber.filter { (number, _) -> number != Math.floor(number) && number <= MAX_PLAUSIBLE_INDEX && number !in numbers }
            .map { (_, part) -> SeriesVolume.Missing(part.ordinal, part.title.ifBlank { null }, part.year) }
        val missing = gaps + between
        return (present + missing).sortedWith(compareBy<SeriesVolume> { sortKey(it.index) }.thenBy { (it as? SeriesVolume.Present)?.title })
    }
}
