package nl.wdhertog.library.data.repository

import nl.wdhertog.library.data.db.Book
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.db.ReadingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class YearOverviewTest {
    private var id = 1L
    private fun read(title: String, author: String, pages: Int?, start: LocalDate?, end: LocalDate, rating: Double? = null): Pair<ReadingSession, BookListItem> {
        val book = Book(id = id++, title = title, pages = pages)
        val session = ReadingSession(id = book.id, bookId = book.id, status = ReadingStatus.FINISHED, startedAt = start, finishedAt = end, rating = rating)
        return session to BookListItem(book, listOf(author), null)
    }

    @Test
    fun `highlights of a reading year`() {
        val all = listOf(
            read("Dictator", "Robert Harris", 400, LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 20), 5.0),
            read("Imperium", "Robert Harris", 500, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 3), 4.5),
            read("De avonden", "Gerard Reve", 250, null, LocalDate.of(2026, 3, 30), 5.0),
            read("Dun", "Iemand", null, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)),
        )
        val stats = ReadingStats.forYear(2026, all.map { it.first }, all.associate { it.second.book.id to it.second }, goal = 12)
        val overview = YearOverview.of(stats)

        assertEquals(4, overview.count)
        assertEquals("Imperium", overview.thickest?.item?.book?.title)
        assertEquals("Robert Harris" to 2, overview.topAuthor)
        assertEquals(setOf("Dictator", "De avonden"), overview.bestRated.map { it.item.book.title }.toSet())
        assertEquals(2 to 2, overview.busiestMonth) // maart
        assertEquals("Imperium" to 3L, overview.fastest?.let { it.first.item.book.title to it.second })
        assertEquals("Dictator", overview.first?.item?.book?.title)
        assertEquals("Dun", overview.last?.item?.book?.title)
        assertEquals(383, overview.averagePages)
        assertTrue(overview.shareText().startsWith("Mijn 2026 in boeken"))
        assertTrue(overview.shareText().contains("Meest gelezen: Robert Harris (2 boeken)"))
    }

    @Test
    fun `one book - no top author, no last book`() {
        val one = listOf(read("Dictator", "Robert Harris", 400, null, LocalDate.of(2026, 1, 20)))
        val overview = YearOverview.of(ReadingStats.forYear(2026, one.map { it.first }, one.associate { it.second.book.id to it.second }, goal = null))
        assertNull(overview.topAuthor)
        assertNull(overview.last)
        assertEquals(emptyList<ReadEntry>(), overview.bestRated)
    }
}
