package nl.wdhertog.library.data.repository

import nl.wdhertog.library.data.db.Ownership
import nl.wdhertog.library.data.db.SeriesBookRow
import nl.wdhertog.library.data.db.SeriesPart
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesOrderTest {

    private fun row(workId: Long, index: String?, bookId: Long = workId, language: String = "nl", read: Boolean = false) =
        SeriesBookRow(workId, "Werk $workId", index, "Auteur", bookId, "Boek $bookId", language, Ownership.OWNED, null, null, read)

    @Test
    fun `years are not volume numbers`() {
        val volumes = SeriesOrder.volumes(listOf(row(1, "2022")), totalVolumes = null)
        assertEquals(1, volumes.size)
    }

    @Test
    fun `sort keys`() {
        assertEquals(2.0, SeriesOrder.sortKey("2"), 0.0)
        assertEquals(0.5, SeriesOrder.sortKey("0,5"), 0.0)
        assertEquals(4.0, SeriesOrder.sortKey("IV"), 0.0)
        assertEquals(3.0, SeriesOrder.sortKey("3a"), 0.0)
        assertEquals(Double.MAX_VALUE, SeriesOrder.sortKey(null), 0.0)
    }

    @Test
    fun `volumes in order with editions grouped and gaps`() {
        val volumes = SeriesOrder.volumes(
            listOf(row(3, "3"), row(1, "1", bookId = 10, language = "en", read = true), row(1, "1", bookId = 11), row(5, null), row(2, "0.5")),
            totalVolumes = 4,
        )
        assertEquals(listOf("0.5", "1", "2", "3", "4", null), volumes.map { it.index })
        val first = volumes[1] as SeriesVolume.Present
        assertEquals(listOf(10L, 11L), first.books.map { it.bookId })
        assertEquals(true, first.read)
        assertEquals(listOf("2", "4"), volumes.filterIsInstance<SeriesVolume.Missing>().map { it.index })
    }

    @Test
    fun `missing volumes get titles from Wikidata, up to its last part`() {
        val parts = listOf(
            SeriesPart(1, "1", "Erbarmen", 2007, "Q1"),
            SeriesPart(1, "2", "De fazantenmoordenaars", 2008, "Q2"),
            SeriesPart(1, "2.5", "Een novelle", 2009, "Q25"),
            SeriesPart(1, "4", "Dossier 64", 2010, "Q4"),
        )
        val volumes = SeriesOrder.volumes(listOf(row(1, "1"), row(3, "3")), totalVolumes = null, parts = parts)
        assertEquals(listOf("1", "2", "2.5", "3", "4"), volumes.map { it.index })
        val missing = volumes.filterIsInstance<SeriesVolume.Missing>()
        assertEquals(listOf("De fazantenmoordenaars", "Een novelle", "Dossier 64"), missing.map { it.title })
        assertEquals(2010, missing.last().year)
    }

    @Test
    fun `without Wikidata a gap has no title`() {
        val missing = SeriesOrder.volumes(listOf(row(1, "1"), row(3, "3")), totalVolumes = null).filterIsInstance<SeriesVolume.Missing>()
        assertEquals(listOf(SeriesVolume.Missing("2")), missing)
    }

    @Test
    fun `a sparse Wikidata series is no reliable total`() {
        assertEquals(null, SeriesOrder.completeTotal(listOf(SeriesPart(1, "13", "Poirot", null, null))))
        assertEquals(4, SeriesOrder.completeTotal((1..4).map { SeriesPart(1, "$it", "Deel $it", null, null) }))
        val volumes = SeriesOrder.volumes(listOf(row(1, "1")), totalVolumes = null, parts = listOf(SeriesPart(1, "13", "Poirot", null, null)))
        assertEquals(listOf("1"), volumes.map { it.index })
    }

    @Test
    fun `own missing parts extend the series and beat Wikidata titles`() {
        val parts = listOf(SeriesPart(1, "2", "Wikidata-titel", 2008, "Q2"))
        val manual = listOf(ManualPart("2", "Mijn titel"), ManualPart("5", "Deel vijf"))
        val volumes = SeriesOrder.volumes(listOf(row(1, "1")), totalVolumes = null, parts = parts, manual = manual)
        assertEquals(listOf("1", "2", "3", "4", "5"), volumes.map { it.index })
        val missing = volumes.filterIsInstance<SeriesVolume.Missing>().associateBy { it.index }
        assertEquals("Mijn titel", missing.getValue("2").title)
        assertEquals(true, missing.getValue("2").manual)
        assertEquals(null, missing.getValue("3").title)
        assertEquals("Deel vijf", missing.getValue("5").title)
    }

    @Test
    fun `an own part you now have disappears`() {
        val volumes = SeriesOrder.volumes(listOf(row(1, "1"), row(2, "2")), totalVolumes = null, manual = listOf(ManualPart("2", "Deel twee")))
        assertEquals(0, volumes.filterIsInstance<SeriesVolume.Missing>().size)
    }

    @Test
    fun `manual parts survive encoding, empty is null`() {
        val parts = listOf(ManualPart("10", "Tien"), ManualPart("2", "Twee \"quote\""))
        assertEquals(parts.sortedBy { SeriesOrder.sortKey(it.ordinal) }, ManualParts.decode(ManualParts.encode(parts)))
        assertEquals(null, ManualParts.encode(emptyList()))
        assertEquals(emptyList<ManualPart>(), ManualParts.decode("rommel"))
        assertEquals(10, ManualParts.highest(ManualParts.encode(parts)))
    }
}
