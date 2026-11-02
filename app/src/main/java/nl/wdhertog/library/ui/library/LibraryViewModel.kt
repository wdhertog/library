package nl.wdhertog.library.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import nl.wdhertog.library.data.repository.ReadingRepository
import kotlinx.coroutines.launch
import nl.wdhertog.library.data.db.LocationType
import nl.wdhertog.library.data.repository.BookListItem
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.data.repository.LocationNode
import nl.wdhertog.library.data.repository.LocationRepository
import nl.wdhertog.library.data.repository.LocationTree
import nl.wdhertog.library.lookup.EnrichScheduler
import nl.wdhertog.library.util.TextNormalizer

enum class SortOrder(val label: String) {
    TITLE("Titel"),
    AUTHOR("Auteur"),
    ADDED("Laatst toegevoegd"),
    YEAR("Jaar van uitgave"),
    LOCATION("Locatie (plankvolgorde)"),
}

enum class ReadFilter(val label: String) {
    ALL("Lezen"),
    READ("Gelezen"),
    UNREAD("Ongelezen"),
    READING("Bezig"),
}

data class LibraryUiState(
    val loading: Boolean = true,
    val items: List<BookListItem> = emptyList(),
    val totalCount: Int = 0,
    val query: String = "",
    val sort: SortOrder = SortOrder.TITLE,
    val language: String? = null,
    val availableLanguages: List<String> = emptyList(),
    /** Filter op deze locatie inclusief alles erbinnen. */
    val location: LocationNode? = null,
    val locationTree: List<LocationNode> = emptyList(),
    /** Pad per locatie-id, voor de 📍-regel in de lijst. */
    val locationLabels: Map<Long, String> = emptyMap(),
    val owner: String? = null,
    val availableOwners: List<String> = emptyList(),
    val favoritesOnly: Boolean = false,
    val read: ReadFilter = ReadFilter.ALL,
) {
    val anyFilter: Boolean get() = language != null || location != null || owner != null || favoritesOnly || read != ReadFilter.ALL
}

private data class Filters(
    val query: String = "",
    val sort: SortOrder = SortOrder.TITLE,
    val language: String? = null,
    val locationId: Long? = null,
    val owner: String? = null,
    val favoritesOnly: Boolean = false,
    val read: ReadFilter = ReadFilter.ALL,
)

/** Welke boeken (gezien per werk) gelezen zijn en welke je nu leest. */
private data class ReadState(val read: Set<Long>, val reading: Set<Long>)

/** Lijstitem met vooraf berekende sorteersleutels, zodat sorteren en filteren snel blijven. */
private class Indexed(val item: BookListItem) {
    /** Leeg voor net gescande boeken zonder titel; die komen onderaan. */
    val titleKey = TextNormalizer.titleSortKey(item.book.title)
    val authorKey = item.authors.firstOrNull()?.let(TextNormalizer::authorSortKey) ?: "￿"
}

class LibraryViewModel(
    private val repository: BookRepository,
    private val locations: LocationRepository,
    private val scheduler: EnrichScheduler,
    reading: ReadingRepository,
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

    private val filters = MutableStateFlow(Filters())

    /** Los van [state], zodat het zoekveld direct reageert terwijl er gefilterd wordt. */
    val queryText: StateFlow<String> = filters.map { it.query }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val readState = combine(reading.observeReadBookIds(), reading.observeReadingBookIds()) { read, current ->
        ReadState(read.toSet(), current.toSet())
    }

    private val indexed = repository.observeLibrary()
        .map { items -> items.map(::Indexed) }
        .flowOn(Dispatchers.Default)

    val state: StateFlow<LibraryUiState> =
        combine(indexed, locations.observeTree(), filters, readState) { all, tree, filters, readState ->
            val flat = LocationTree.flatten(tree)
            val byId = flat.associateBy { it.id }
            val selected = filters.locationId?.let(byId::get)
            val inSelected = selected?.let(LocationTree::descendantIds)
            val treeOrder = flat.withIndex().associate { (index, node) -> node.id to index }
            val words = TextNormalizer.normalize(filters.query).split(' ').filter(String::isNotEmpty)
            val visible = all
                .filter { filters.language == null || it.item.book.language == filters.language }
                .filter { inSelected == null || it.item.book.locationId in inSelected }
                .filter { filters.owner == null || it.item.book.owner == filters.owner }
                .filter { !filters.favoritesOnly || it.item.book.favorite }
                .filter {
                    when (filters.read) {
                        ReadFilter.ALL -> true
                        ReadFilter.READ -> it.item.book.id in readState.read
                        ReadFilter.UNREAD -> it.item.book.id !in readState.read && it.item.book.id !in readState.reading
                        ReadFilter.READING -> it.item.book.id in readState.reading
                    }
                }
                .filter { entry -> words.all { entry.item.book.searchText.contains(it) } }
                .sortedWith(comparatorFor(filters.sort, treeOrder))
                .map { it.item }
            LibraryUiState(
                loading = false,
                items = visible,
                totalCount = all.size,
                query = filters.query,
                sort = filters.sort,
                language = filters.language,
                availableLanguages = all.mapNotNull { it.item.book.language }.distinct().sorted(),
                location = selected,
                locationTree = tree,
                locationLabels = flat.associate { it.id to it.pathLabel },
                owner = filters.owner,
                availableOwners = all.mapNotNull { it.item.book.owner }.distinct().sortedBy { it.lowercase() },
                favoritesOnly = filters.favoritesOnly,
                read = filters.read,
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setQuery(value: String) = filters.update { it.copy(query = value) }

    fun setSort(value: SortOrder) = filters.update { it.copy(sort = value) }

    fun setLanguage(value: String?) = filters.update { it.copy(language = value) }

    fun setLocation(value: Long?) = filters.update { it.copy(locationId = value) }

    fun setOwner(value: String?) = filters.update { it.copy(owner = value) }

    fun setFavoritesOnly(value: Boolean) = filters.update { it.copy(favoritesOnly = value) }

    fun setRead(value: ReadFilter) = filters.update { it.copy(read = value) }

    /** Alle filters uit (zoektekst en sortering blijven). */
    fun clearFilters() = filters.update { Filters(query = it.query, sort = it.sort) }

    suspend fun createLocation(name: String, type: LocationType, parentId: Long?): Long = locations.add(name, type, parentId)

    private fun comparatorFor(order: SortOrder, treeOrder: Map<Long, Int>): Comparator<Indexed> = when (order) {
        SortOrder.TITLE -> compareBy<Indexed> { it.titleKey.isEmpty() }.thenBy { it.titleKey }
        SortOrder.AUTHOR -> compareBy<Indexed> { it.authorKey }
            .thenBy { it.item.series?.seriesName }
            .thenBy { it.item.series?.seriesIndex?.toDoubleOrNull() }
            .thenBy { it.titleKey }
        SortOrder.ADDED -> compareByDescending { it.item.book.addedAt }
        SortOrder.YEAR -> compareBy<Indexed, Int?>(nullsLast()) { it.item.book.publishedYear }.thenBy { it.titleKey }
        // Zoals de boeken in huis staan: locaties in boomvolgorde, daarbinnen plankvolgorde.
        SortOrder.LOCATION -> compareBy<Indexed, Int?>(nullsLast()) { it.item.book.locationId?.let(treeOrder::get) }
            .thenBy(nullsLast()) { it.item.book.shelfPosition }
            .thenBy { it.titleKey }
    }
}
