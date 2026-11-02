package nl.wdhertog.library.importer

import nl.wdhertog.library.data.db.ReadingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

class TableImporterTest {

    private val header = listOf("Voornaam", "Tussenvoegsel", "Achternaam", "Titel", "Jaar van uitgave", "ISBN", "Fictie", "Gelezen", "Waardering", "Uitgeleend", "Emigreerbaar", "Is al weg")

    @Test
    fun `columns are guessed from header and values`() {
        val table = Table(header, listOf(listOf("Gerard", "", "Reve", "De avonden", "1947", "", "1", "0", "", "", "Ja", "")))
        val guessed = header.indices.map { ColumnTarget.guess(header[it], table.column(it)) }
        assertEquals(
            listOf(
                ColumnTarget.AUTHOR_FIRST, ColumnTarget.AUTHOR_INFIX, ColumnTarget.AUTHOR_LAST, ColumnTarget.TITLE, ColumnTarget.YEAR,
                ColumnTarget.ISBN, ColumnTarget.TAG_IF_TRUE, ColumnTarget.READ, ColumnTarget.RATING, ColumnTarget.LENT_TO,
                ColumnTarget.TAG_IF_TRUE, ColumnTarget.GONE,
            ),
            guessed,
        )
    }

    @Test
    fun `rows become books`() {
        val targets = header.map { ColumnTarget.guess(it, emptyList()) }.toMutableList()
        targets[6] = ColumnTarget.TAG_IF_TRUE
        targets[10] = ColumnTarget.NOTE_WITH_HEADER
        val table = Table(
            header,
            listOf(
                listOf("Jan", "Van der", "Berg", "Het boek", "2008", "9789071376320", "1", "1", "4", "buren", "Misschien", ""),
                listOf("", "", "", "", "", "", "", "", "", "", "", ""),
                listOf("R.", "", "Ábalos", "Grimpow", "", "978606097086", "0", "0", "", "1", "Nee", "1"),
            ),
        )
        val result = TableImporter.toBooks(table, targets)
        assertEquals(1, result.skipped)
        val (first, second) = result.books
        assertEquals(listOf("Jan van der Berg"), first.authors)
        assertEquals(2008, first.publishedYear)
        assertEquals("9789071376320", first.isbn13)
        assertEquals(listOf("Fictie"), first.tags)
        assertEquals(listOf(ImportedReading(ReadingStatus.FINISHED, rating = 4.0)), first.readings)
        assertEquals("buren", first.lentTo)
        assertEquals("Emigreerbaar: Misschien", first.notes)

        assertEquals(null, second.isbn13)
        assertEquals(emptyList<ImportedReading>(), second.readings)
        assertFalse(second.owned)
        assertEquals("", second.lentTo)
    }

    @Test
    fun `author names in one column`() {
        assertEquals(listOf("J.R.R. Tolkien"), TableImporter.authors("Tolkien, J.R.R.", null, null, null))
        assertEquals(listOf("Rita Monaldi", "Francesco Sorti"), TableImporter.authors("Rita Monaldi & Francesco Sorti", null, null, null))
        assertEquals(listOf("A B", "C D", "E F"), TableImporter.authors("A B, C D, E F", null, null, null))
        assertEquals(listOf("De Graaf"), TableImporter.authors(null, null, "De", "Graaf"))
    }

    @Test
    fun `dates and languages`() {
        assertEquals(LocalDate.of(2024, 3, 23), TableImporter.date("23-3-2024"))
        assertEquals(LocalDate.of(2024, 3, 23), TableImporter.date("45374"))
        assertEquals("nl", TableImporter.languageCode("Nederlands"))
        assertEquals("en", TableImporter.languageCode("English"))
        assertEquals("de", TableImporter.languageCode("ger"))
    }
}
