package nl.wdhertog.library.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import nl.wdhertog.library.data.db.LibraryDatabase

/** Alle badges uit de huidige gegevens (zie Achievements), voor Lezen, het jaaroverzicht en meldingen. */
class BadgeService(
    db: LibraryDatabase,
    private val books: BookRepository,
    private val reading: ReadingRepository,
    private val series: SeriesRepository,
) {
    private val loans = db.loanDao()
    private val authors = db.authorDao()

    fun observe(): Flow<List<Badge>> =
        combine(
            books.observeLibrary(),
            combine(reading.observeSessions(), reading.observeLoggedDays(), ::Pair),
            reading.observeGoals(),
            series.observeSummaries(),
            combine(loans.observeLentDates(), authors.observeCountries(), ::Pair),
        ) { library, (sessions, logged), goals, summaries, (lent, countries) ->
            Achievements.evaluate(
                sessions, library.associateBy { it.book.id }, goals, summaries,
                readingDays = Streaks.readingDays(sessions, logged), lentDates = lent,
                authorCountries = countries.associate { it.name to it.country },
            )
        }.flowOn(Dispatchers.Default)

    suspend fun current(): List<Badge> = observe().first()

    companion object {
        /** Badges die in [after] (vaker) behaald zijn dan in [before]: nieuw verdiend. */
        fun newlyEarned(before: List<Badge>, after: List<Badge>): List<Badge> {
            val earlier = before.associate { it.id to it.times }
            return after.filter { it.achieved && it.times > (earlier[it.id] ?: 0) }
        }
    }
}
