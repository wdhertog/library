package nl.wdhertog.library.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Datumteksten voor uitleningen: "15 nov", "sinds 3 weken", "2 dagen te laat". */
object LoanDates {

    private val dutch = Locale.forLanguageTag("nl")
    private val sameYear = DateTimeFormatter.ofPattern("d MMM", dutch)
    private val otherYear = DateTimeFormatter.ofPattern("d MMM yyyy", dutch)

    fun format(date: LocalDate, today: LocalDate = LocalDate.now()): String =
        (if (date.year == today.year) sameYear else otherYear).format(date).replace(".", "")

    /** Hoe lang geleden, grof afgerond: "vandaag", "sinds 4 dagen", "sinds 3 weken", "sinds 5 maanden". */
    fun since(date: LocalDate, today: LocalDate = LocalDate.now()): String {
        val days = ChronoUnit.DAYS.between(date, today)
        return when {
            days <= 0 -> "sinds vandaag"
            days == 1L -> "sinds gisteren"
            days < 14 -> "sinds $days dagen"
            days < 60 -> "sinds ${days / 7} weken"
            days < 730 -> "sinds ${ChronoUnit.MONTHS.between(date, today)} maanden"
            else -> "sinds ${ChronoUnit.YEARS.between(date, today)} jaar"
        }
    }

    data class DueStatus(val text: String, val overdue: Boolean, val dueSoon: Boolean)

    /** Status ten opzichte van de terugbrengdatum. */
    fun due(dueAt: LocalDate, today: LocalDate = LocalDate.now()): DueStatus {
        val days = ChronoUnit.DAYS.between(today, dueAt)
        return when {
            days < 0 -> DueStatus(if (days == -1L) "1 dag te laat" else "${-days} dagen te laat", overdue = true, dueSoon = false)
            days == 0L -> DueStatus("vandaag terug", overdue = false, dueSoon = true)
            days <= 3 -> DueStatus("terug vóór ${format(dueAt, today)}", overdue = false, dueSoon = true)
            else -> DueStatus("terug vóór ${format(dueAt, today)}", overdue = false, dueSoon = false)
        }
    }
}
