package nl.wdhertog.library.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import nl.wdhertog.library.lookup.EnrichScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import nl.wdhertog.library.data.repository.BookListItem
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.util.TextNormalizer

enum class SortOrder(val label: String) {
    TITLE("Titel"),
    AUTHOR("Auteur"),
    ADDED("Laatst toegevoegd"),
    YEAR("Jaar van uitgave"),
}

data class LibraryUiState(
    val loading: Boolean = true,
    val items: List<BookListItem> = emptyList(),
    val totalCount: Int = 0,
    val query: String = "",
    val sort: SortOrder = SortOrder.TITLE,
    val language: String? = null,
    val availableLanguages: List<String> = emptyList(),
)

private data class Filters(val query: String, val sort: SortOrder, val language: String?)

/** Lijstitem met vooraf berekende sorteersleutels, zodat sorteren en filteren snel blijven. */
private class Indexed(val item: BookListItem) {
    /** Leeg voor net gescande boeken zonder titel; die komen onderaan. */
    val titleKey = TextNormalizer.titleSortKey(item.book.title)
    val authorKey = item.authors.firstOrNull()?.let(TextNormalizer::authorSortKey) ?: "￿"
}

class LibraryViewModel(
    private val repository: BookRepository,
    private val scheduler: EnrichScheduler,
) : ViewModel() {

    /** Aantal boeken waarvan de gegevens nog op de achtergrond worden opgehaald. */
    val awaitingLookup: StateFlow<Int> = repository.observeAwaitingEnrichmentCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** "Bibliotheek bijwerken": niet gevonden boeken opnieuw proberen en de wachtrij starten. */
    fun updateLibrary() {
        viewModelScope.launch {
            val requeued = repository.requeueFailed()
            scheduler.schedule()
            val pending = awaitingLookup.value + requeued
            _messages.send(
                if (pending == 0) "Alle boeken zijn al bijgewerkt"
                else "Gegevens ophalen voor $pending ${if (pending == 1) "boek" else "boeken"}",
            )
        }
    }

    private val query = MutableStateFlow("")

    /** Los van [state], zodat het zoekveld direct reageert terwijl er gefilterd wordt. */
    val queryText: StateFlow<String> = query

    private val sort = MutableStateFlow(SortOrder.TITLE)
    private val language = MutableStateFlow<String?>(null)

    private val indexed = repository.observeLibrary()
        .map { items -> items.map(::Indexed) }
        .flowOn(Dispatchers.Default)

    val state: StateFlow<LibraryUiState> =
        combine(indexed, combine(query, sort, language, ::Filters)) { all, filters ->
            val words = TextNormalizer.normalize(filters.query).split(' ').filter(String::isNotEmpty)
            val visible = all
                .filter { filters.language == null || it.item.book.language == filters.language }
                .filter { entry -> words.all { entry.item.book.searchText.contains(it) } }
                .sortedWith(comparatorFor(filters.sort))
                .map { it.item }
            LibraryUiState(
                loading = false,
                items = visible,
                totalCount = all.size,
                query = filters.query,
                sort = filters.sort,
                language = filters.language,
                availableLanguages = all.mapNotNull { it.item.book.language }.distinct().sorted(),
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setQuery(value: String) {
        query.value = value
    }

    fun setSort(value: SortOrder) {
        sort.value = value
    }

    fun setLanguage(value: String?) {
        language.value = value
    }

    private fun comparatorFor(order: SortOrder): Comparator<Indexed> = when (order) {
        SortOrder.TITLE -> compareBy<Indexed> { it.titleKey.isEmpty() }.thenBy { it.titleKey }
        SortOrder.AUTHOR -> compareBy<Indexed> { it.authorKey }
            .thenBy { it.item.series?.seriesName }
            .thenBy { it.item.series?.seriesIndex?.toDoubleOrNull() }
            .thenBy { it.titleKey }
        SortOrder.ADDED -> compareByDescending { it.item.book.addedAt }
        SortOrder.YEAR -> compareBy<Indexed, Int?>(nullsLast()) { it.item.book.publishedYear }.thenBy { it.titleKey }
    }
}
