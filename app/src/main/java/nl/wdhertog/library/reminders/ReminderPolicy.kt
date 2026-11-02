package nl.wdhertog.library.reminders

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Wanneer er een herinnering komt: vanaf de herinneringsdatum, daarna hooguit eens per week. */
object ReminderPolicy {

    const val REPEAT_DAYS = 7L

    /**
     * @param remindOn de dag van de eerste herinnering (terugbrengdatum, of de dag ervoor bij zelf geleende boeken)
     * @param lastNotified wanneer er voor deze uitlening (met deze datum) al een herinnering was
     */
    fun shouldNotify(remindOn: LocalDate, today: LocalDate, lastNotified: LocalDate?): Boolean = when {
        today < remindOn -> false
        lastNotified == null || lastNotified < remindOn -> true
        else -> ChronoUnit.DAYS.between(lastNotified, today) >= REPEAT_DAYS
    }
}
