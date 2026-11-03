package nl.wdhertog.library.importer

import nl.wdhertog.library.data.db.BookFormat
import nl.wdhertog.library.data.db.DatePrecision
import nl.wdhertog.library.data.db.DisposalReason
import nl.wdhertog.library.data.db.ReadingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HirtParserTest {

    private val export = HirtParser.parse(javaClass.getResource("/importer/hirt_sample.json")!!.readText())
    private fun book(hirtId: Long) = export.books.single { it.hirtId == hirtId }

    @Test
    fun `only owned print books are imported`() {
        assertEquals(listOf(10L, 11L, 14L, 15L), export.books.map { it.hirtId })
        assertEquals(2, export.skipped)
        assertEquals(mapOf(2026 to 16), export.goals)
    }

    @Test
    fun `everything when asked, with borrowed books and e-books`() {
        val all = HirtParser.parse(javaClass.getResource("/importer/hirt_sample.json")!!.readText(), includeAll = true)
        assertEquals(listOf(10L, 11L, 12L, 13L, 14L, 15L), all.books.map { it.hirtId })
        assertEquals(0, all.skipped)
        val ebook = all.books.single { it.hirtId == 12L }
        assertEquals(BookFormat.EBOOK, ebook.format)
        assertTrue(ebook.owned)
        // "geleend" bij een boek dat je niet hebt: zelf geleend en teruggebracht, geen uitlening.
        val borrowed = all.books.single { it.hirtId == 13L }
        assertFalse(borrowed.owned)
        assertEquals(DisposalReason.BORROWED, borrowed.disposalReason)
        assertNull(borrowed.lentTo)
        // "geleend" bij je eigen boek blijft een uitlening.
        assertEquals("", all.books.single { it.hirtId == 11L }.lentTo)
        assertNull(all.books.single { it.hirtId == 11L }.disposalReason)
    }

    @Test
    fun `series is taken from the title`() {
        val duin = book(10)
        assertEquals("Duin", duin.title)
        assertEquals("Duin", duin.seriesName)
        assertEquals("1", duin.seriesIndex)
        assertEquals(listOf("Max Schuchart"), duin.translators)
        assertEquals(BookFormat.PAPERBACK, duin.format)
        assertEquals(listOf("fictie", "sci-fi"), duin.tags)
        assertTrue(duin.favorite)
        assertEquals(LocalDate.of(2012, 2, 1), duin.addedAt)
    }

    @Test
    fun `readings are sorted, rating goes to the last one, january first means year only`() {
        val reads = book(10).readings
        assertEquals(2, reads.size)
        assertEquals(LocalDate.of(2009, 1, 1), reads[0].finishedAt)
        assertEquals(DatePrecision.YEAR, reads[0].datePrecision)
        assertNull(reads[0].rating)
        assertEquals(DatePrecision.DAY, reads[1].datePrecision)
        assertEquals(3.25, reads[1].rating!!, 0.0)
        assertEquals("Mooi", reads[1].review)
        assertTrue(reads.all { it.status == ReadingStatus.FINISHED })
    }

    @Test
    fun `cleans title and authors, lent tag becomes a loan`() {
        val book = book(11)
        assertEquals("De drietand, de heks en de draak", book.title)
        assertEquals(listOf("Jason Matthews"), book.authors)
        assertEquals(emptyList<String>(), book.translators)
        assertEquals("", book.lentTo)
        assertEquals(emptyList<String>(), book.tags)
        assertEquals("Tales from Alagaësia", book.seriesName)
        assertEquals(
            listOf(ImportedReading(ReadingStatus.READING, startedAt = LocalDate.of(2026, 10, 22), progressPage = 200, progressPercent = 63)),
            book.readings,
        )
    }

    @Test
    fun `status without reads`() {
        assertEquals(listOf(ImportedReading(ReadingStatus.ABANDONED)), book(14).readings)
        val toRead = book(15)
        assertTrue(toRead.wantToRead)
        assertEquals(emptyList<ImportedReading>(), toRead.readings)
    }

    @Test(expected = HirtParser.FormatException::class)
    fun `rejects other json`() {
        HirtParser.parse("""{"foo": 1}""")
    }
}
