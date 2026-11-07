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
import nl.wdhertog.library.data.repository.editionFacts
import nl.wdhertog.library.data.repository.Editions
import nl.wdhertog.library.data.repository.ReadingRepository
import kotlinx.coroutines.launch
import nl.wdhertog.library.data.db.LocationType
import nl.wdhertog.library.data.db.Ownership
import nl.wdhertog.library.data.repository.BookListItem
import nl.wdhertog.library.data.repository.BookStatus
import nl.wdhertog.library.data.repository.LoanRepository
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

/** Standaard alleen wat in huis staat (eigen en geleende boeken); de leesgeschiedenis telt alles. */
enum class OwnershipFilter(val label: String) {
    IN_HOUSE("In bezit"),
    NOT_OWNED("Niet in bezit"),
    ALL("Alles"),
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
    val genre: String? = null,
    /** Genres die in je bibliotheek voorkomen (met aantal), voor de filter. */
    val availableGenres: List<Pair<String, Int>> = emptyList(),
    /** Filter op deze locatie inclusief alles erbinnen. */
    val location: LocationNode? = null,
    val locationTree: List<LocationNode> = emptyList(),
    /** Pad per locatie-id, voor de 📍-regel in de lijst. */
    val locationLabels: Map<Long, String> = emptyMap(),
    val owner: String? = null,
    val availableOwners: List<String> = emptyList(),
    val favoritesOnly: Boolean = false,
    val read: ReadFilter = ReadFilter.ALL,
    val ownership: OwnershipFilter = OwnershipFilter.IN_HOUSE,
    val multipleEditions: Boolean = false,
    /** Zijn er boeken die je niet (meer) hebt? Anders is de bezit-filter overbodig. */
    val hasNotOwned: Boolean = false,
    /** Statusregel per boek: uitgeleend, bezig of gelezen. */
    val statuses: Map<Long, BookStatus> = emptyMap(),
) {
    val anyFilter: Boolean
        get() = activeFilterCount > 0

    val activeFilterCount: Int
        get() = listOf(
            language != null, genre != null, location != null, owner != null, favoritesOnly, read != ReadFilter.ALL,
            ownership != OwnershipFilter.IN_HOUSE, multipleEditions,
        ).count { it }
}

private data class Filters(
    val query: String = "",
    val sort: SortOrder = SortOrder.TITLE,
    val language: String? = null,
    val genre: String? = null,
    val locationId: Long? = null,
    val owner: String? = null,
    val favoritesOnly: Boolean = false,
    val read: ReadFilter = ReadFilter.ALL,
    val ownership: OwnershipFilter = OwnershipFilter.IN_HOUSE,
    /** Alleen boeken waarvan je (binnen de bezit-filter) twee of meer edities van hetzelfde werk hebt. */
    val multipleEditions: Boolean = false,
)

/** Welke boeken (gezien per werk) gelezen zijn en welke je nu leest. */
private data class ReadState(val read: Set<Long>, val reading: Set<Long>, val statuses: Map<Long, BookStatus> = emptyMap())

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
    loans: LoanRepository? = null,
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
            scheduler.schedule(manual = true)
            val pending = awaitingLookup.value + requeued
            _messages.send(
                if (pending == 0) "Alle boeken zijn al bijgewerkt"
                else "Gegevens ophalen voor $pending ${if (pending == 1) "boek" else "boeken"}",
            )
        }
    }

    private val filters = MutableStateFlow(Filters())

    /**
     * Een andere filter of sortering gekozen: de lijst begint dan weer bovenaan. Alleen bij een echte
     * wijziging door de gebruiker, niet bij terugkeren naar het scherm (dan blijft de scrollpositie staan).
     */
    private val _scrollToTop = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    val scrollToTop: kotlinx.coroutines.flow.Flow<Unit> = _scrollToTop.receiveAsFlow()

    private fun changeFilters(change: (Filters) -> Filters) {
        val before = filters.value
        filters.update(change)
        if (filters.value != before) _scrollToTop.trySend(Unit)
    }

    /** Los van [state], zodat het zoekveld direct reageert terwijl er gefilterd wordt. */
    val queryText: StateFlow<String> = filters.map { it.query }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val readState = combine(
        reading.observeReadBookIds(),
        reading.observeReadingBookIds(),
        reading.observeLastFinished(),
        reading.observeProgress(),
        loans?.observeActiveLoans() ?: kotlinx.coroutines.flow.flowOf(emptyList()),
    ) { read, current, finished, progress, active ->
        val readIds = read.toSet()
        ReadState(readIds, current.toSet(), BookStatus.of(active, progress, finished, readIds))
    }

    private val indexed = repository.observeLibrary()
        .map { items -> items.map(::Indexed) }
        .flowOn(Dispatchers.Default)

    val state: StateFlow<LibraryUiState> =
        combine(indexed, locations.observeTree(), filters, readState, repository.observePrimaryWorks()) { all, tree, filters, readState, works ->
            val workOf = works.associate { it.bookId to it.workId }
            val chosenPrimary = works.associate { it.workId to it.primaryBookUuid }
            val genresOf = works.associate { it.bookId to nl.wdhertog.library.util.Genres.decode(it.genres) }
            val flat = LocationTree.flatten(tree)
            val byId = flat.associateBy { it.id }
            val selected = filters.locationId?.let(byId::get)
            val inSelected = selected?.let(LocationTree::descendantIds)
            val treeOrder = flat.withIndex().associate { (index, node) -> node.id to index }
            val words = TextNormalizer.normalize(filters.query).split(' ').filter(String::isNotEmpty)
            // Zoeken vindt ook boeken die je niet (meer) hebt, zolang je niet zelf een bezit-filter kiest.
            val ownership = if (words.isNotEmpty() && filters.ownership == OwnershipFilter.IN_HOUSE) OwnershipFilter.ALL else filters.ownership
            val inScope = all.filter {
                when (ownership) {
                    OwnershipFilter.IN_HOUSE -> it.item.book.ownership != Ownership.NOT_OWNED
                    OwnershipFilter.NOT_OWNED -> it.item.book.ownership == Ownership.NOT_OWNED
                    OwnershipFilter.ALL -> true
                }
            }
            // Edities: werken waarvan binnen de gekozen bezit-filter minstens twee boeken staan.
            val editionGroups = if (filters.multipleEditions) {
                inScope.groupBy { workOf[it.item.book.id] }.filterKeys { it != null }.filterValues { it.size >= 2 }
            } else {
                emptyMap()
            }
            // Edities van één werk naast elkaar: op de (eerste) titel van het werk, daarbinnen op titel.
            val groupKey = editionGroups.mapValues { (_, books) -> books.minOf { it.titleKey } }
            // Binnen een groep de hoofdeditie bovenaan (zelf gekozen, of volgens Editions).
            val primaryOfGroup = editionGroups.mapValues { (workId, books) ->
                Editions.primaryOf(books, chosenPrimary[workId]) { it.item.book.editionFacts }?.item?.book?.id
            }
            val visible = (if (filters.multipleEditions) editionGroups.values.flatten() else inScope)
                .filter { filters.language == null || it.item.book.language == filters.language }
                .filter { filters.genre == null || filters.genre in genresOf[it.item.book.id].orEmpty() }
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
                .sortedWith(
                    if (filters.multipleEditions) compareBy<Indexed> { groupKey[workOf[it.item.book.id]] }.thenBy { workOf[it.item.book.id] }
                        .thenByDescending { primaryOfGroup[workOf[it.item.book.id]] == it.item.book.id }.thenBy { it.titleKey }
                    else comparatorFor(filters.sort, treeOrder),
                )
                .map { it.item }
            LibraryUiState(
                loading = false,
                items = visible,
                totalCount = inScope.size,
                query = filters.query,
                sort = filters.sort,
                language = filters.language,
                availableLanguages = all.mapNotNull { it.item.book.language }.distinct().sorted(),
                genre = filters.genre,
                availableGenres = inScope.flatMap { genresOf[it.item.book.id].orEmpty() }.groupingBy { it }.eachCount()
                    .toList().sortedBy { it.first },
                location = selected,
                locationTree = tree,
                locationLabels = flat.associate { it.id to it.pathLabel },
                owner = filters.owner,
                availableOwners = all.mapNotNull { it.item.book.owner }.distinct().sortedBy { it.lowercase() },
                favoritesOnly = filters.favoritesOnly,
                read = filters.read,
                ownership = filters.ownership,
                multipleEditions = filters.multipleEditions,
                hasNotOwned = all.any { it.item.book.ownership == Ownership.NOT_OWNED },
                statuses = readState.statuses,
            )
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setQuery(value: String) = changeFilters { it.copy(query = value) }

    fun setSort(value: SortOrder) = changeFilters { it.copy(sort = value) }

    fun setLanguage(value: String?) = changeFilters { it.copy(language = value) }

    fun setGenre(value: String?) = changeFilters { it.copy(genre = value) }

    fun setLocation(value: Long?) = changeFilters { it.copy(locationId = value) }

    fun setOwner(value: String?) = changeFilters { it.copy(owner = value) }

    fun setFavoritesOnly(value: Boolean) = changeFilters { it.copy(favoritesOnly = value) }

    fun setRead(value: ReadFilter) = changeFilters { it.copy(read = value) }

    fun setOwnership(value: OwnershipFilter) = changeFilters { it.copy(ownership = value) }

    fun setMultipleEditions(value: Boolean) = changeFilters { it.copy(multipleEditions = value) }

    /** Alle filters uit (zoektekst en sortering blijven). */
    fun clearFilters() = changeFilters { Filters(query = it.query, sort = it.sort) }

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
