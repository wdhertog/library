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

    /** Een deel dat je niet hebt; titel en jaar als Wikidata ze kent, of zelf toegevoegd ([manual]). */
    data class Missing(override val index: String, val title: String? = null, val year: Int? = null, val manual: Boolean = false) : SeriesVolume
}

/** Een zelf toegevoegd ontbrekend deel. */
@kotlinx.serialization.Serializable
data class ManualPart(val ordinal: String, val title: String)

/** Opslag van zelf toegevoegde delen in Series.manualParts. */
object ManualParts {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val serializer = kotlinx.serialization.builtins.ListSerializer(ManualPart.serializer())

    fun decode(text: String?): List<ManualPart> =
        text?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    /** Leeg wordt null, zodat een serie zonder eigen delen geen veld heeft. */
    fun encode(parts: List<ManualPart>): String? =
        parts.takeIf { it.isNotEmpty() }?.sortedBy { SeriesOrder.sortKey(it.ordinal) }?.let { json.encodeToString(serializer, it) }

    /** Hoogste hele nummer, voor het totaal in de serielijst. */
    fun highest(text: String?): Int? =
        decode(text).mapNotNull { it.ordinal.trim().toIntOrNull() }.filter { it in 1..SeriesOrder.MAX_PLAUSIBLE_INDEX }.maxOrNull()
}

private fun factsOf(row: SeriesBookRow) = EditionFacts(
    row.bookUuid, row.ownership == nl.wdhertog.library.data.db.Ownership.OWNED, (row.coverFile ?: row.coverUrl) != null,
    row.language, row.addedAt.toEpochMilli(),
)

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
    fun volumes(
        rows: List<SeriesBookRow>,
        totalVolumes: Int?,
        parts: List<SeriesPart> = emptyList(),
        manual: List<ManualPart> = emptyList(),
    ): List<SeriesVolume> {
        val present = rows.groupBy { it.workId }.map { (workId, unordered) ->
            // De hoofdeditie eerst: die cover. Zelf gekozen, dan ook die titel (anders de titel van het werk).
            val primaryUuid = unordered.first().primaryBookUuid
            val books = Editions.ordered(unordered, primaryUuid, ::factsOf)
            val first = books.first()
            SeriesVolume.Present(
                index = first.seriesIndex,
                workId = workId,
                title = if (Editions.isChosen(books, primaryUuid, ::factsOf)) first.bookTitle else first.workTitle.ifBlank { first.bookTitle },
                authors = first.authors,
                books = books,
                read = books.any { it.read },
            )
        }
        val numbers = present.map { sortKey(it.index) }.filter { it != Double.MAX_VALUE }
        val highestNumber = numbers.maxOrNull()?.toInt()?.takeIf { it <= MAX_PLAUSIBLE_INDEX } ?: 0
        val partsByNumber = parts.filter { sortKey(it.ordinal) != Double.MAX_VALUE }.associateBy { sortKey(it.ordinal) }
        val manualByNumber = manual.filter { sortKey(it.ordinal) != Double.MAX_VALUE }.associateBy { sortKey(it.ordinal) }
        val highestPart = completeTotal(parts) ?: 0
        val highestManual = manualByNumber.keys.filter { it == Math.floor(it) && it <= MAX_PLAUSIBLE_INDEX }.maxOrNull()?.toInt() ?: 0
        val highest = maxOf(highestNumber, totalVolumes ?: 0, highestPart, highestManual)
        // Een zelf ingevulde titel gaat voor die van Wikidata.
        fun missing(number: Double, ordinal: String): SeriesVolume.Missing {
            manualByNumber[number]?.let { return SeriesVolume.Missing(it.ordinal, it.title.ifBlank { null }, null, manual = true) }
            val part = partsByNumber[number]
            return SeriesVolume.Missing(ordinal, part?.title?.ifBlank { null }, part?.year)
        }
        // Geen enkel deel met een nummer: dan is niet te zien welke nummers je mist. Toon alleen wat je zelf als
        // ontbrekend opgaf. (Eén los boek zonder nummer naast genummerde delen is gewoon een bijdeel.)
        if (present.isNotEmpty() && numbers.isEmpty()) {
            val own = manual.filter { m -> sortKey(m.ordinal) !in numbers }
                .map { SeriesVolume.Missing(it.ordinal, it.title.ifBlank { null }, null, manual = true) }
            return (present + own).sortedWith(compareBy<SeriesVolume> { sortKey(it.index) }.thenBy { (it as? SeriesVolume.Present)?.title })
        }
        val gaps = (1..highest).filter { n -> numbers.none { it == n.toDouble() } }.map { n -> missing(n.toDouble(), n.toString()) }
        // Tussennummers (bijv. een novelle 2.5) die je niet hebt.
        val between = (partsByNumber.keys + manualByNumber.keys)
            .filter { number -> number != Math.floor(number) && number <= MAX_PLAUSIBLE_INDEX && number !in numbers }
            .map { number -> missing(number, manualByNumber[number]?.ordinal ?: partsByNumber.getValue(number).ordinal) }
        val missing = gaps + between
        return (present + missing).sortedWith(compareBy<SeriesVolume> { sortKey(it.index) }.thenBy { (it as? SeriesVolume.Present)?.title })
    }
}
