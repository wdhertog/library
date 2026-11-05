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

    private fun days(vararg spec: String): Set<LocalDate> = spec.flatMap { range ->
        val (from, to) = if (".." in range) range.split("..") else listOf(range, range)
        generateSequence(LocalDate.parse(from)) { it.plusDays(1) }.takeWhile { !it.isAfter(LocalDate.parse(to)) }.toList()
    }.toSet()

    @Test
    fun `day streak allows one free day per week`() {
        // 2026-11-02 is een maandag. Week 1: woensdag vrij; week 2 (vanaf 9 nov): elke dag.
        val read = days("2026-11-02..2026-11-03", "2026-11-05..2026-11-12")
        assertEquals(11, Streaks.currentDays(read, LocalDate.of(2026, 11, 12)))
        // Vandaag (13 nov) nog niet gelezen: de streak loopt nog.
        assertEquals(11, Streaks.currentDays(read, LocalDate.of(2026, 11, 13)))
        // 13 en 14 nov allebei niet: twee gemiste dagen in één week, weg.
        assertEquals(0, Streaks.currentDays(read, LocalDate.of(2026, 11, 15)))
    }

    @Test
    fun `two free days in one week break the streak`() {
        // Ma 2 en wo 4 nov gelezen, di 3 en do 5 nov niet: alleen vanaf wo 4 telt.
        val read = days("2026-11-01", "2026-11-02", "2026-11-04", "2026-11-06..2026-11-08")
        assertEquals(5, Streaks.currentDays(read, LocalDate.of(2026, 11, 8)))
        val sessions = listOf(read("2026-11-04"))
        val streaks = Streaks.of(sessions, LocalDate.of(2026, 11, 8), read)
        assertEquals(Streak(current = 5, longest = 5, thisPeriod = true), streaks.days)
    }

    @Test
    fun `longest day streak and when it was reached`() {
        val read = days("2025-03-03..2025-03-09", "2025-03-11..2025-03-20", "2025-06-01..2025-06-03")
        // 3 t/m 20 maart, met maandag 10 maart als vrije dag: 18 dagen.
        assertEquals(LocalDate.of(2025, 3, 9), Streaks.daysReachedOn(read, 7))
        assertEquals(18, Streaks.of(emptyList(), LocalDate.of(2025, 6, 3), read).days.longest)
        assertEquals(3, Streaks.of(emptyList(), LocalDate.of(2025, 6, 3), read).days.current)
        assertEquals(null, Streaks.daysReachedOn(read, 30))
    }

    @Test
    fun `reading days come from the log and from session dates`() {
        val sessions = listOf(
            ReadingSession(id = 1, bookId = 1, startedAt = LocalDate.of(2026, 1, 1), finishedAt = LocalDate.of(2026, 1, 9), status = ReadingStatus.FINISHED),
            ReadingSession(id = 2, bookId = 2, startedAt = LocalDate.of(2026, 1, 1), finishedAt = LocalDate.of(2026, 1, 1), datePrecision = DatePrecision.YEAR, status = ReadingStatus.FINISHED),
        )
        assertEquals(
            setOf(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 9)),
            Streaks.readingDays(sessions, listOf(LocalDate.of(2026, 1, 5))),
        )
    }
}
