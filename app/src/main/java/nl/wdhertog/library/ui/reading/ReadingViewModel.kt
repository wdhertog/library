package nl.wdhertog.library.ui.reading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.wdhertog.library.data.repository.Achievements
import nl.wdhertog.library.data.repository.Badge
import nl.wdhertog.library.data.repository.BookListItem
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.data.repository.ReadEntry
import nl.wdhertog.library.data.repository.ReadingRepository
import nl.wdhertog.library.data.repository.ReadingStats
import nl.wdhertog.library.data.repository.ReadingStreaks
import nl.wdhertog.library.data.repository.SeriesRepository
import nl.wdhertog.library.data.repository.Streaks
import nl.wdhertog.library.data.repository.YearStats
import java.time.LocalDate

data class ReadingUiState(
    val years: List<Int>,
    val current: List<ReadEntry>,
    val wantToRead: List<BookListItem>,
    val stats: YearStats,
    val undated: Int,
    val streaks: ReadingStreaks,
    val badges: List<Badge>,
)

class ReadingViewModel(
    books: BookRepository,
    private val reading: ReadingRepository,
    series: SeriesRepository,
) : ViewModel() {

    val year = MutableStateFlow(LocalDate.now().year)

    val state: StateFlow<ReadingUiState?> =
        combine(
            books.observeLibrary(), reading.observeSessions(), reading.observeGoals(), series.observeSummaries(), year,
        ) { library, sessions, goals, seriesSummaries, year ->
            val byId = library.associateBy { it.book.id }
            ReadingUiState(
                years = ReadingStats.years(sessions),
                current = ReadingStats.current(sessions, byId),
                wantToRead = library.filter { it.book.wantToRead }.sortedByDescending { it.book.addedAt },
                stats = ReadingStats.forYear(year, sessions, byId, goals.firstOrNull { it.year == year }?.target),
                undated = ReadingStats.undated(sessions),
                streaks = Streaks.of(sessions),
                badges = Achievements.evaluate(sessions, byId, goals, seriesSummaries),
            )
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setGoal(year: Int, target: Int?) {
        viewModelScope.launch { reading.setGoal(year, target) }
    }
}
