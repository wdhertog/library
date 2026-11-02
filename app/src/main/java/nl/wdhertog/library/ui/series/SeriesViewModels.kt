package nl.wdhertog.library.ui.series

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.wdhertog.library.data.db.SeriesSummary
import nl.wdhertog.library.data.repository.SeriesDetail
import nl.wdhertog.library.data.repository.SeriesRepository
import nl.wdhertog.library.ui.navigation.SeriesDetailRoute

class SeriesListViewModel(series: SeriesRepository) : ViewModel() {
    val summaries: StateFlow<List<SeriesSummary>?> = series.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

class SeriesDetailViewModel(savedStateHandle: SavedStateHandle, private val series: SeriesRepository) : ViewModel() {

    /** Kan veranderen: na samenvoegen ga je verder met de serie die overblijft. */
    private val seriesId = MutableStateFlow(savedStateHandle.toRoute<SeriesDetailRoute>().seriesId)

    val detail: StateFlow<SeriesDetail?> = seriesId.flatMapLatest { series.observeDetail(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Andere series, om mee samen te voegen. */
    val others: StateFlow<List<SeriesSummary>> = series.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun rename(name: String) {
        viewModelScope.launch { seriesId.value = series.rename(seriesId.value, name) }
    }

    fun mergeInto(otherId: Long) {
        viewModelScope.launch {
            series.merge(seriesId.value, otherId)
            seriesId.value = otherId
        }
    }

    fun setTotal(total: Int?) {
        viewModelScope.launch { series.setTotalVolumes(seriesId.value, total) }
    }

    fun setIndex(workId: Long, index: String?) {
        viewModelScope.launch { series.setIndex(workId, index) }
    }

    fun removeFromSeries(workId: Long) {
        viewModelScope.launch { series.removeFromSeries(workId) }
    }
}
