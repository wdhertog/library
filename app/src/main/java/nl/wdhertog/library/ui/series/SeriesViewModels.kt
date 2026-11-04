package nl.wdhertog.library.ui.series

import android.content.SharedPreferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.wdhertog.library.data.db.SeriesSummary
import nl.wdhertog.library.data.repository.SeriesDetail
import nl.wdhertog.library.data.repository.SeriesDuplicate
import nl.wdhertog.library.data.repository.SeriesDuplicates
import nl.wdhertog.library.data.repository.SeriesRepository
import nl.wdhertog.library.ui.navigation.SeriesDetailRoute

class SeriesListViewModel(
    private val series: SeriesRepository,
    private val prefs: SharedPreferences,
    mayLookUp: () -> Boolean = { false },
) : ViewModel() {
    val summaries: StateFlow<List<SeriesSummary>?> = series.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Delen (en daarmee het totaal) ophalen voor series die dat nog niet hebben; op de achtergrond.
        if (mayLookUp()) viewModelScope.launch { series.refreshStaleParts() }
    }

    private val ignored = MutableStateFlow(prefs.getStringSet(KEY_IGNORED, emptySet()).orEmpty())

    /** Series die waarschijnlijk dubbel zijn (bijv. dezelfde serie uit twee bronnen). */
    val duplicates: StateFlow<List<SeriesDuplicate>> = combine(summaries, ignored) { list, skip ->
        list?.let { SeriesDuplicates.find(it, skip) }.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun merge(duplicate: SeriesDuplicate) {
        viewModelScope.launch { series.merge(duplicate.other.id, duplicate.keep.id) }
    }

    fun ignore(duplicate: SeriesDuplicate) {
        ignored.value = ignored.value + duplicate.key
        prefs.edit().putStringSet(KEY_IGNORED, ignored.value).apply()
    }

    private companion object {
        const val KEY_IGNORED = "ignored-duplicate-series"
    }
}

@OptIn(ExperimentalCoroutinesApi::class) // flatMapLatest
class SeriesDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val series: SeriesRepository,
    mayLookUp: () -> Boolean = { false },
) : ViewModel() {

    /** Kan veranderen: na samenvoegen ga je verder met de serie die overblijft. */
    private val seriesId = MutableStateFlow(savedStateHandle.toRoute<SeriesDetailRoute>().seriesId)

    val lookingUp = MutableStateFlow(false)

    init {
        if (mayLookUp()) viewModelScope.launch { series.refreshParts(seriesId.value) }
    }

    /** "Delen opzoeken op Wikidata": altijd opnieuw; [done] krijgt een melding. */
    fun lookUpParts(done: (String) -> Unit) {
        lookingUp.value = true
        viewModelScope.launch {
            val found = series.refreshParts(seriesId.value, force = true)
            lookingUp.value = false
            done(
                when (found) {
                    null -> "Wikidata is nu niet bereikbaar"
                    0 -> "Wikidata kent geen genummerde delen van deze serie"
                    1 -> "1 deel gevonden op Wikidata"
                    else -> "$found delen gevonden op Wikidata"
                },
            )
        }
    }

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

    /** Een ontbrekend deel zelf invullen of aanpassen; een lege titel haalt het weg. */
    fun setManualPart(ordinal: String, title: String, previousOrdinal: String? = null) {
        viewModelScope.launch { series.setManualPart(seriesId.value, ordinal, title, previousOrdinal) }
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
