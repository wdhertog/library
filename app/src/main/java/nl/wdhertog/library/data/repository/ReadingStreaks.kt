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

/** @property days dagen op rij (v2), met één vrije dag per week (ma–zo). */
data class ReadingStreaks(val months: Streak, val weeks: Streak, val days: Streak = Streak(0, 0, false))

/**
 * Leesstreaks v1 (ontwerp §12.6): alleen uit de datum van uitlezen, dus zonder dagelijks bij te houden.
 * Alleen sessies met een volledige datum tellen; van boeken met alleen een jaar weten we de maand niet.
 */
object Streaks {

    fun of(
        sessions: List<ReadingSession>,
        today: LocalDate = LocalDate.now(),
        /** Leesdagen voor de dagstreak; zie [readingDays]. */
        days: Set<LocalDate> = readingDays(sessions, emptyList()),
    ): ReadingStreaks {
        val dates = finishedDates(sessions)
        return ReadingStreaks(
            months = streak(dates.map(::monthIndex).toSortedSet(), monthIndex(today)),
            weeks = streak(dates.map(::weekIndex).toSortedSet(), weekIndex(today)),
            days = Streak(currentDays(days, today), dayRuns(days, emptyList()).longest, today in days),
        )
    }

    /**
     * Leesdagen: dagen met bijgewerkte voortgang ([logged], tabel reading_log) plus de begin- en
     * einddatum van elke sessie met een volledige datum.
     */
    fun readingDays(sessions: List<ReadingSession>, logged: Collection<LocalDate>): Set<LocalDate> =
        buildSet {
            addAll(logged)
            sessions.filter { it.datePrecision == DatePrecision.DAY }.forEach { s ->
                s.startedAt?.let(::add)
                s.finishedAt?.let(::add)
            }
        }

    /** De dag waarop een dagstreak voor het eerst [length] dagen lang was; null als nog nooit. */
    fun daysReachedOn(days: Set<LocalDate>, length: Int): LocalDate? = dayRuns(days, listOf(length)).reached[length]

    /**
     * Lopende dagstreak: terug vanaf vandaag (telt vandaag nog niet, dan vanaf gisteren), zolang er per
     * week (ma–zo) hooguit één dag zonder lezen tussen zit. Telt de dagen van de eerste tot de laatste
     * leesdag, vrije dagen ertussen inbegrepen.
     */
    internal fun currentDays(days: Set<LocalDate>, today: LocalDate): Int {
        val first = days.minOrNull() ?: return 0
        val end = if (today in days) today else today.minusDays(1)
        val missed = mutableSetOf<Long>()
        var day = end
        var newestRead: LocalDate? = null
        var oldestRead: LocalDate? = null
        while (!day.isBefore(first)) {
            if (day in days) {
                if (newestRead == null) newestRead = day
                oldestRead = day
            } else if (!missed.add(weekIndex(day))) {
                break
            }
            day = day.minusDays(1)
        }
        return if (newestRead == null || oldestRead == null) 0 else (newestRead.toEpochDay() - oldestRead.toEpochDay() + 1).toInt()
    }

    private class DayRuns(val longest: Int, val reached: Map<Int, LocalDate>)

    /**
     * Alle dagstreaks van oud naar nieuw: de langste, en per lengte uit [thresholds] de dag waarop die
     * voor het eerst gehaald werd. Een tweede gemiste dag in een week breekt de reeks; de nieuwe begint
     * dan bij de eerste leesdag na de eerste gemiste dag van die week.
     */
    private fun dayRuns(days: Set<LocalDate>, thresholds: List<Int>): DayRuns {
        val sorted = days.sorted()
        if (sorted.isEmpty()) return DayRuns(0, emptyMap())
        var longest = 0
        val reached = mutableMapOf<Int, LocalDate>()
        var start: LocalDate? = null
        val firstMiss = mutableMapOf<Long, LocalDate>() // week → eerste gemiste dag in de lopende reeks
        var day = sorted.first()
        val last = sorted.last()
        while (!day.isAfter(last)) {
            if (day in days) {
                if (start == null) start = day
                val length = (day.toEpochDay() - start.toEpochDay() + 1).toInt()
                longest = maxOf(longest, length)
                thresholds.filter { it !in reached && length >= it }.forEach { reached[it] = day }
            } else if (start != null) {
                val week = weekIndex(day)
                val earlier = firstMiss[week]
                if (earlier == null) {
                    firstMiss[week] = day
                } else {
                    // Opnieuw beginnen na de eerste gemiste dag van deze week, als daarna nog gelezen is.
                    var next = earlier.plusDays(1)
                    while (next.isBefore(day) && next !in days) next = next.plusDays(1)
                    firstMiss.clear()
                    if (next.isBefore(day)) {
                        start = next
                        firstMiss[week] = day
                    } else {
                        start = null
                    }
                }
            }
            day = day.plusDays(1)
        }
        return DayRuns(longest, reached)
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
