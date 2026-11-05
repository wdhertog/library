package nl.wdhertog.library.data.repository

import nl.wdhertog.library.data.db.DatePrecision
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.db.ReadingStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Een reeks aaneengesloten maanden of weken met minstens één uitgelezen boek.
 *
 * @property current de lopende reeks. Die loopt nog zolang de vorige periode meetelde: in een nieuwe
 *   maand heb je tot het eind van die maand om hem voort te zetten.
 * @property thisPeriod of de huidige maand/week al meetelt.
 */
data class Streak(val current: Int, val longest: Int, val thisPeriod: Boolean)

data class ReadingStreaks(val months: Streak, val weeks: Streak)

/**
 * Leesstreaks v1 (ontwerp §12.6): alleen uit de datum van uitlezen, dus zonder dagelijks bij te houden.
 * Alleen sessies met een volledige datum tellen; van boeken met alleen een jaar weten we de maand niet.
 */
object Streaks {

    fun of(sessions: List<ReadingSession>, today: LocalDate = LocalDate.now()): ReadingStreaks {
        val dates = finishedDates(sessions)
        return ReadingStreaks(
            months = streak(dates.map(::monthIndex).toSortedSet(), monthIndex(today)),
            weeks = streak(dates.map(::weekIndex).toSortedSet(), weekIndex(today)),
        )
    }

    /**
     * De dag waarop je voor het eerst [length] maanden op rij haalde: het eerste uitgelezen boek in de
     * laatste maand van die reeks. Null als dat nog nooit lukte.
     */
    fun monthsReachedOn(sessions: List<ReadingSession>, length: Int): LocalDate? {
        val dates = finishedDates(sessions).sorted()
        val months = dates.map(::monthIndex).distinct()
        var run = 0
        var previous: Long? = null
        for (month in months) {
            run = if (previous != null && month == previous + 1) run + 1 else 1
            previous = month
            if (run >= length) return dates.first { monthIndex(it) == month }
        }
        return null
    }

    private fun finishedDates(sessions: List<ReadingSession>): List<LocalDate> =
        sessions.filter { it.status == ReadingStatus.FINISHED && it.datePrecision == DatePrecision.DAY }
            .mapNotNull { it.finishedAt }

    private fun streak(periods: Set<Long>, now: Long): Streak {
        var longest = 0
        var run = 0
        var previous: Long? = null
        for (period in periods) { // gesorteerd
            run = if (previous != null && period == previous + 1) run + 1 else 1
            longest = maxOf(longest, run)
            previous = period
        }
        val thisPeriod = now in periods
        val end = if (thisPeriod) now else now - 1
        var current = 0
        while (end - current in periods) current++
        return Streak(current = current, longest = longest, thisPeriod = thisPeriod)
    }

    private fun monthIndex(date: LocalDate): Long = date.year * 12L + date.monthValue - 1

    /** Weken van maandag tot en met zondag, genummerd vanaf 1970. */
    private fun weekIndex(date: LocalDate): Long =
        Math.floorDiv(date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay() + 3, 7L)
}
