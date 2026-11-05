package nl.wdhertog.library.data.repository

import nl.wdhertog.library.data.db.Book
import nl.wdhertog.library.data.db.BookSeriesRow
import nl.wdhertog.library.data.db.DatePrecision
import nl.wdhertog.library.data.db.ReadingGoal
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.db.ReadingStatus
import nl.wdhertog.library.data.db.SeriesSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AchievementsTest {

    private var nextId = 1L

    private fun item(id: Long, pages: Int? = 200, language: String? = "nl", author: String = "Auteur $id", series: BookSeriesRow? = null) =
        BookListItem(Book(id = id, title = "Boek $id", pages = pages, language = language), listOf(author), series)

    private fun read(bookId: Long, date: LocalDate?, precision: DatePrecision = DatePrecision.DAY) =
        ReadingSession(id = nextId++, bookId = bookId, finishedAt = date, datePrecision = precision, status = ReadingStatus.FINISHED)

    private fun List<Badge>.get(id: String) = first { it.id == id }

    @Test
    fun `book counts with the date of the tenth book`() {
        val books = (1L..12L).associateWith { item(it) }
        val start = LocalDate.of(2025, 1, 1)
        val sessions = (1L..12L).map { read(it, start.plusDays(it)) } +
            ReadingSession(id = 999, bookId = 1, status = ReadingStatus.ABANDONED, finishedAt = start)
        val badges = Achievements.evaluate(sessions, books, emptyList(), emptyList())
        val ten = badges.get("books-10")
        assertTrue(ten.achieved)
        assertEquals(start.plusDays(10), ten.achievedOn)
        val fifty = badges.get("books-50")
        assertFalse(fifty.achieved)
        assertEquals(12, fifty.progress)
        assertNull(fifty.achievedOn)
        // Pagina's: 12 × 200 = 2.400, en geen dikke pil.
        assertEquals(2400, badges.get("pages-10000").progress)
        assertFalse(badges.get("thick").achieved)
    }

    @Test
    fun `undated books count but give no date`() {
        val books = (1L..10L).associateWith { item(it) }
        val sessions = (1L..9L).map { read(it, LocalDate.of(2024, 5, it.toInt())) } + read(10, null)
        val ten = Achievements.evaluate(sessions, books, emptyList(), emptyList()).get("books-10")
        assertTrue(ten.achieved)
        assertNull(ten.achievedOn)
        // Een jaar-datum geeft alleen het jaar.
        val yearOnly = (1L..9L).map { read(it, LocalDate.of(2024, 5, it.toInt())) } + read(10, LocalDate.of(2025, 1, 1), DatePrecision.YEAR)
        val badge = Achievements.evaluate(yearOnly, books, emptyList(), emptyList()).get("books-10")
        assertEquals(LocalDate.of(2025, 1, 1), badge.achievedOn)
        assertTrue(badge.yearOnly)
    }

    @Test
    fun `goal, languages, thick book, author and rereads`() {
        val books = mapOf(
            1L to item(1, pages = 1200, language = "en", author = "Tolkien"),
            2L to item(2, language = "de", author = "Tolkien"),
            3L to item(3, language = "NL", author = "Tolkien"),
            4L to item(4, language = null, author = "Tolkien"),
        )
        val sessions = listOf(
            read(1, LocalDate.of(2025, 2, 1)), read(2, LocalDate.of(2025, 3, 1)), read(3, LocalDate.of(2025, 4, 1)),
            read(4, LocalDate.of(2026, 1, 1)), read(4, LocalDate.of(2026, 2, 1)), read(4, LocalDate.of(2026, 3, 1)),
        )
        val goals = listOf(ReadingGoal(2025, 3), ReadingGoal(2026, 5))
        val badges = Achievements.evaluate(sessions, books, goals, emptyList())
        badges.get("goal").let {
            assertTrue(it.achieved)
            assertEquals(1, it.times)
            assertEquals(LocalDate.of(2025, 4, 1), it.achievedOn)
        }
        assertEquals(LocalDate.of(2025, 4, 1), badges.get("languages-3").achievedOn)
        assertEquals(3, badges.get("languages-5").progress)
        assertEquals(LocalDate.of(2025, 2, 1), badges.get("thick").achievedOn)
        assertEquals(4, badges.get("author").progress) // boek 4 drie keer telt één keer
        assertEquals(LocalDate.of(2026, 3, 1), badges.get("reread").achievedOn)
        assertEquals(listOf("reread", "goal", "languages-3"), Achievements.recent(badges).map { it.id })
    }

    @Test
    fun `series completed when every part is read`() {
        fun row(book: Long, index: String) = BookSeriesRow(book, 7, "Serie", index)
        val books = mapOf(1L to item(1, series = row(1, "1")), 2L to item(2, series = row(2, "2")), 3L to item(3, series = row(3, "3")))
        val sessions = listOf(read(1, LocalDate.of(2025, 1, 1)), read(3, LocalDate.of(2025, 2, 1)), read(2, LocalDate.of(2025, 6, 1)), read(1, LocalDate.of(2026, 1, 1)))
        val done = SeriesSummary(id = 7, name = "Serie", totalVolumes = 3, works = 3, owned = 3, read = 3, maxIndex = 3)
        val badge = Achievements.evaluate(sessions, books, emptyList(), listOf(done)).get("series")
        assertTrue(badge.achieved)
        assertEquals(LocalDate.of(2025, 6, 1), badge.achievedOn) // herlezen van deel 1 telt niet
        val halfway = done.copy(totalVolumes = 5)
        val open = Achievements.evaluate(sessions, books, emptyList(), listOf(halfway)).get("series")
        assertFalse(open.achieved)
        assertEquals(60, open.progress)
    }
}
