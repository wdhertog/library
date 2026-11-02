package nl.wdhertog.library.data.repository

import nl.wdhertog.library.data.db.Book
import nl.wdhertog.library.data.db.DatePrecision
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.db.ReadingStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ReadingStatsTest {

    private fun item(id: Long, pages: Int?, language: String?) =
        BookListItem(Book(id = id, title = "Boek $id", pages = pages, language = language), listOf("Auteur"), null)

    private val books = mapOf(1L to item(1, 300, "nl"), 2L to item(2, 200, "en"), 3L to item(3, null, "nl"))

    private val sessions = listOf(
        ReadingSession(id = 1, bookId = 1, finishedAt = LocalDate.of(2026, 3, 10), status = ReadingStatus.FINISHED, rating = 4.0),
        ReadingSession(id = 2, bookId = 2, finishedAt = LocalDate.of(2026, 3, 20), status = ReadingStatus.FINISHED, rating = 3.0),
        ReadingSession(id = 3, bookId = 3, finishedAt = LocalDate.of(2026, 1, 1), datePrecision = DatePrecision.YEAR, status = ReadingStatus.FINISHED),
        ReadingSession(id = 4, bookId = 1, finishedAt = LocalDate.of(2025, 6, 1), status = ReadingStatus.FINISHED),
        ReadingSession(id = 5, bookId = 2, startedAt = LocalDate.of(2026, 10, 22), status = ReadingStatus.READING),
        ReadingSession(id = 6, bookId = 3, finishedAt = LocalDate.of(2026, 5, 5), status = ReadingStatus.ABANDONED),
        ReadingSession(id = 7, bookId = 3, status = ReadingStatus.FINISHED),
    )

    @Test
    fun `year statistics`() {
        val stats = ReadingStats.forYear(2026, sessions, books, goal = 16)
        assertEquals(listOf(2L, 1L, 3L), stats.finished.map { it.session.id })
        assertEquals(2, stats.perMonth[2])
        assertEquals(2, stats.perMonth.sum())
        assertEquals(1, stats.yearOnly)
        assertEquals(500, stats.pages)
        assertEquals(3.5, stats.averageRating!!, 0.0)
        assertEquals(listOf("nl" to 2, "en" to 1), stats.languages)
    }

    @Test
    fun `current, years and undated`() {
        assertEquals(listOf(5L), ReadingStats.current(sessions, books).map { it.session.id })
        assertEquals(listOf(2026, 2025), ReadingStats.years(sessions, LocalDate.of(2026, 11, 2)))
        assertEquals(1, ReadingStats.undated(sessions))
    }
}
