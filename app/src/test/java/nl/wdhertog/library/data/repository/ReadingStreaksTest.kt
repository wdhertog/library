package nl.wdhertog.library.data.repository

import nl.wdhertog.library.data.db.DatePrecision
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.db.ReadingStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ReadingStreaksTest {

    private var nextId = 1L
    private fun read(date: String, precision: DatePrecision = DatePrecision.DAY, status: ReadingStatus = ReadingStatus.FINISHED) =
        ReadingSession(id = nextId++, bookId = 1, finishedAt = LocalDate.parse(date), datePrecision = precision, status = status)

    @Test
    fun `months in a row, with the current month still open`() {
        val sessions = listOf(
            read("2026-01-15"), read("2026-02-03"), read("2026-03-30"), // 3 op rij
            read("2026-05-01"), read("2026-06-10"), read("2026-06-20"), read("2026-07-31"), read("2026-08-02"), read("2026-09-12"), read("2026-10-01"),
        )
        // November nog niets: oktober telt, de reeks loopt nog.
        val nov = Streaks.of(sessions, LocalDate.of(2026, 11, 5)).months
        assertEquals(Streak(current = 6, longest = 6, thisPeriod = false), nov)
        // In december is november gemist.
        assertEquals(0, Streaks.of(sessions, LocalDate.of(2026, 12, 1)).months.current)
        // Een boek in november zet hem voort.
        val withNov = Streaks.of(sessions + read("2026-11-04"), LocalDate.of(2026, 11, 5)).months
        assertEquals(Streak(current = 7, longest = 7, thisPeriod = true), withNov)
    }

    @Test
    fun `year boundary, weeks and what does not count`() {
        val sessions = listOf(
            read("2025-12-31"), read("2026-01-02"), // ma 29 dec t/m zo 4 jan: één week
            read("2026-01-05"), // volgende week
            read("2026-01-20", status = ReadingStatus.ABANDONED),
            read("2026-01-14", precision = DatePrecision.YEAR),
            ReadingSession(id = 99, bookId = 1, status = ReadingStatus.FINISHED),
        )
        val streaks = Streaks.of(sessions, LocalDate.of(2026, 1, 11))
        assertEquals(Streak(current = 2, longest = 2, thisPeriod = true), streaks.months)
        assertEquals(Streak(current = 2, longest = 2, thisPeriod = true), streaks.weeks)
        assertEquals(0, Streaks.of(sessions, LocalDate.of(2026, 1, 19)).weeks.current)
    }

    @Test
    fun `date a streak was first reached`() {
        val sessions = listOf(read("2024-01-10"), read("2024-02-10"), read("2024-04-01"), read("2024-05-20"), read("2024-05-02"), read("2024-06-01"))
        assertEquals(LocalDate.of(2024, 2, 10), Streaks.monthsReachedOn(sessions, 2))
        assertEquals(LocalDate.of(2024, 6, 1), Streaks.monthsReachedOn(sessions, 3))
        assertNull(Streaks.monthsReachedOn(sessions, 4))
        assertEquals(Streak(0, 0, false), Streaks.of(emptyList(), LocalDate.of(2026, 1, 1)).months)
    }
}
