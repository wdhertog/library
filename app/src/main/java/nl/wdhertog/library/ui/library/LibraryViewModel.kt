package nl.wdhertog.library.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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
    val titleKey = TextNormalizer.titleSortKey(item.book.title)
    val authorKey = item.authors.firstOrNull()?.let(TextNormalizer::authorSortKey) ?: "￿"
}

class LibraryViewModel(repository: BookRepository) : ViewModel() {

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
        SortOrder.TITLE -> compareBy { it.titleKey }
        SortOrder.AUTHOR -> compareBy<Indexed> { it.authorKey }
            .thenBy { it.item.series?.seriesName }
            .thenBy { it.item.series?.seriesIndex?.toDoubleOrNull() }
            .thenBy { it.titleKey }
        SortOrder.ADDED -> compareByDescending { it.item.book.addedAt }
        SortOrder.YEAR -> compareBy<Indexed, Int?>(nullsLast()) { it.item.book.publishedYear }.thenBy { it.titleKey }
    }
}
