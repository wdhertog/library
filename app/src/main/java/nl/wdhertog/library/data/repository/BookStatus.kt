package nl.wdhertog.library.data.repository

import nl.wdhertog.library.data.db.BookFinished
import nl.wdhertog.library.data.db.BookProgress
import nl.wdhertog.library.data.db.LoanRow
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Wat er nu met een boek is, voor de statusregel in de bibliotheek (schetsen, 4 november 2026). */
sealed interface BookStatus {
    /** Uitgeleend aan iemand. */
    data class Lent(val person: String?, val since: LocalDate?) : BookStatus
    data class Reading(val percent: Int?) : BookStatus
    /** Uitgelezen (ook in een andere editie); [year] van de laatste keer. */
    data class Read(val year: Int?) : BookStatus

    companion object {
        /**
         * Per boek de belangrijkste status: uitgeleend gaat voor (het boek is weg), dan bezig, dan gelezen.
         */
        fun of(
            loans: List<LoanRow>,
            progress: List<BookProgress>,
            finished: List<BookFinished>,
            readIds: Set<Long>,
        ): Map<Long, BookStatus> {
            val result = mutableMapOf<Long, BookStatus>()
            finished.forEach { result[it.bookId] = Read(it.finishedAt?.year) }
            readIds.forEach { id -> if (id !in result) result[id] = Read(null) }
            progress.forEach { result[it.bookId] = Reading(it.percent) }
            loans.forEach { result[it.bookId] = Lent(it.personName, it.lentAt) }
            return result
        }

        /** "3 dagen", "2 weken", "3 mnd", "2 jaar". */
        fun durationSince(date: LocalDate, today: LocalDate = LocalDate.now()): String {
            val days = ChronoUnit.DAYS.between(date, today).coerceAtLeast(0)
            val months = ChronoUnit.MONTHS.between(date, today)
            return when {
                days < 1 -> "vandaag"
                days < 14 -> if (days == 1L) "1 dag" else "$days dagen"
                months < 2 -> "${days / 7} weken"
                months < 24 -> "$months mnd"
                else -> "${months / 12} jaar"
            }
        }
    }
}
