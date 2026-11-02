package nl.wdhertog.library.data.repository

import nl.wdhertog.library.data.db.DatePrecision
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.db.ReadingStatus
import java.time.LocalDate

/** Een afgeronde leessessie met het boek erbij, voor lijsten en statistiek. */
data class ReadEntry(val session: ReadingSession, val item: BookListItem)

data class YearStats(
    val year: Int,
    /** Uitgelezen in dit jaar, nieuwste eerst. */
    val finished: List<ReadEntry>,
    /** Per maand (januari = 0); boeken waarvan alleen het jaar bekend is, tellen hier niet mee. */
    val perMonth: List<Int>,
    val yearOnly: Int,
    val pages: Int,
    val averageRating: Double?,
    /** Taalcode → aantal, meeste eerst. */
    val languages: List<Pair<String, Int>>,
    val goal: Int?,
)

/** Rekenwerk voor het Lezen-scherm (zie ontwerp §6.6). */
object ReadingStats {

    /** Lopende sessies, langst bezig eerst. */
    fun current(sessions: List<ReadingSession>, books: Map<Long, BookListItem>): List<ReadEntry> =
        sessions.filter { it.status == ReadingStatus.READING }
            .sortedBy { it.startedAt ?: LocalDate.MAX }
            .mapNotNull { session -> books[session.bookId]?.let { ReadEntry(session, it) } }

    /** Jaren met gelezen boeken, plus dit jaar; nieuwste eerst. */
    fun years(sessions: List<ReadingSession>, today: LocalDate = LocalDate.now()): List<Int> =
        (sessions.filter { it.status == ReadingStatus.FINISHED }.mapNotNull { it.finishedAt?.year } + today.year)
            .distinct().sortedDescending()

    fun forYear(year: Int, sessions: List<ReadingSession>, books: Map<Long, BookListItem>, goal: Int?): YearStats {
        val finished = sessions
            .filter { it.status == ReadingStatus.FINISHED && it.finishedAt?.year == year }
            .sortedWith(compareByDescending<ReadingSession> { it.finishedAt }.thenByDescending { it.id })
            .mapNotNull { session -> books[session.bookId]?.let { ReadEntry(session, it) } }
        val perMonth = MutableList(12) { 0 }
        finished.filter { it.session.datePrecision == DatePrecision.DAY }
            .forEach { perMonth[it.session.finishedAt!!.monthValue - 1]++ }
        val ratings = finished.mapNotNull { it.session.rating }
        return YearStats(
            year = year,
            finished = finished,
            perMonth = perMonth,
            yearOnly = finished.count { it.session.datePrecision == DatePrecision.YEAR },
            pages = finished.sumOf { it.item.book.pages ?: 0 },
            averageRating = ratings.takeIf { it.isNotEmpty() }?.average(),
            languages = finished.mapNotNull { it.item.book.language }.groupingBy { it }.eachCount()
                .toList().sortedByDescending { it.second },
            goal = goal,
        )
    }

    /** Uitgelezen zonder bekende datum: telt in geen enkel jaar mee. */
    fun undated(sessions: List<ReadingSession>): Int =
        sessions.count { it.status == ReadingStatus.FINISHED && it.finishedAt == null }
}
