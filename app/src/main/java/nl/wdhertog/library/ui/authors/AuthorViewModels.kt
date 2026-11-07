package nl.wdhertog.library.ui.authors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.wdhertog.library.data.db.Author
import nl.wdhertog.library.data.db.AuthorSummary
import nl.wdhertog.library.data.db.SeriesSummary
import nl.wdhertog.library.data.repository.AuthorDuplicate
import nl.wdhertog.library.data.repository.AuthorDuplicates
import nl.wdhertog.library.data.repository.AuthorRepository
import nl.wdhertog.library.data.repository.BookListItem
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.data.repository.Editions
import nl.wdhertog.library.data.repository.editionFacts
import nl.wdhertog.library.data.repository.ReadingRepository
import nl.wdhertog.library.data.repository.SeriesOrder
import nl.wdhertog.library.data.repository.SeriesRepository
import nl.wdhertog.library.data.repository.displayTitle
import nl.wdhertog.library.ui.navigation.AuthorRoute
import nl.wdhertog.library.util.TextNormalizer

class AuthorListViewModel(
    private val repository: AuthorRepository,
    private val prefs: android.content.SharedPreferences,
) : ViewModel() {
    val query = MutableStateFlow("")

    val authors: StateFlow<List<AuthorSummary>?> = combine(repository.observeSummaries(), query) { list, q ->
        val needle = TextNormalizer.normalize(q)
        if (needle.isBlank()) list else list.filter { TextNormalizer.normalize(it.name).contains(needle) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val ignored = MutableStateFlow(prefs.getStringSet(KEY_IGNORED, emptySet()).orEmpty())

    /** Auteurs die waarschijnlijk dezelfde zijn in een andere schrijfwijze ("J.R.R. Tolkien", "Tolkien, J.R.R."). */
    val duplicates: StateFlow<List<AuthorDuplicate>> = combine(repository.observeSummaries(), ignored) { list, skip ->
        AuthorDuplicates.find(list, skip)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun merge(duplicate: AuthorDuplicate) {
        viewModelScope.launch { repository.merge(duplicate.other.id, duplicate.keep.id) }
    }

    fun ignore(duplicate: AuthorDuplicate) {
        ignored.value = ignored.value + duplicate.key
        prefs.edit().putStringSet(KEY_IGNORED, ignored.value).apply()
    }

    private companion object {
        const val KEY_IGNORED = "ignored-duplicate-authors"
    }
}

/** Eén werk van de auteur, met jouw edities en vertalingen. */
data class AuthorWork(
    val workId: Long,
    val title: String,
    val seriesName: String?,
    val seriesIndex: String?,
    val books: List<BookListItem>,
    val read: Boolean,
)

data class AuthorPage(
    val author: Author,
    val works: List<AuthorWork>,
    val series: List<SeriesSummary>,
    val books: Int,
    val readWorks: Int,
    val averageRating: Double?,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AuthorViewModel(
    savedStateHandle: SavedStateHandle,
    private val authors: AuthorRepository,
    books: BookRepository,
    reading: ReadingRepository,
    series: SeriesRepository,
    private val mayLookUp: () -> Boolean = { false },
) : ViewModel() {

    private val route = savedStateHandle.toRoute<AuthorRoute>()

    /** Op id, of op naam (vanaf de boekpagina). */
    private val authorId = MutableStateFlow(route.authorId.takeIf { it != 0L })

    val lookingUp = MutableStateFlow(false)

    /** Geen auteur met dit id of deze naam (meer), bijv. na samenvoegen of verwijderen: niet eindeloos laden. */
    val notFound = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            val id = authorId.value ?: authors.findId(route.name)?.also { authorId.value = it }
                ?: run { notFound.value = true; return@launch }
            if (mayLookUp()) authors.refreshInfo(id)
        }
    }

    val page: StateFlow<AuthorPage?> = authorId.filterNotNull().flatMapLatest { id ->
        combine(
            authors.observeAuthor(id),
            authors.observeBookWorks(id),
            books.observeLibrary(),
            combine(reading.observeReadBookIds(), authors.observeAverageRating(id), ::Pair),
            series.observeSummaries(),
        ) { author, links, library, (readIds, rating), allSeries ->
            if (author == null) {
                notFound.value = true
                return@combine null
            }
            notFound.value = false
            val byId = library.associateBy { it.book.id }
            val read = readIds.toSet()
            val works = links.groupBy { it.workId }.mapNotNull { (workId, workLinks) ->
                // De hoofdeditie eerst: die titel en cover.
                val items = Editions.ordered(workLinks.mapNotNull { byId[it.bookId] }, workLinks.first().primaryBookUuid) { it.book.editionFacts }
                val first = items.firstOrNull() ?: return@mapNotNull null
                AuthorWork(
                    workId = workId,
                    title = first.book.displayTitle,
                    seriesName = first.series?.seriesName,
                    seriesIndex = first.series?.seriesIndex,
                    books = items,
                    read = items.any { it.book.id in read },
                )
            }.sortedWith(
                compareBy<AuthorWork> { it.seriesName == null }
                    .thenBy { it.seriesName?.lowercase() }
                    .thenBy { SeriesOrder.sortKey(it.seriesIndex) }
                    .thenBy { w -> w.books.mapNotNull { it.book.publishedYear }.minOrNull() ?: Int.MAX_VALUE }
                    .thenBy { TextNormalizer.titleSortKey(it.title) },
            )
            val seriesIds = links.mapNotNull { link -> byId[link.bookId]?.series?.seriesId }.toSet()
            AuthorPage(
                author = author,
                works = works,
                series = allSeries.filter { it.id in seriesIds },
                books = works.sumOf { it.books.size },
                readWorks = works.count { it.read },
                averageRating = rating,
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Alle auteurs, voor "Samenvoegen met…". */
    val allAuthors: StateFlow<List<AuthorSummary>> = authors.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Naam wijzigen; bestaat de naam al, dan samenvoegen en verder met die auteur. */
    fun rename(name: String) {
        val id = authorId.value ?: return
        viewModelScope.launch { authorId.value = authors.rename(id, name) }
    }

    /** Deze auteur opgaan in [intoId]; de pagina toont daarna die auteur. */
    fun mergeInto(intoId: Long) {
        val id = authorId.value ?: return
        viewModelScope.launch {
            authors.merge(id, intoId)
            authorId.value = intoId
        }
    }

    /** Opnieuw bij Wikidata kijken. */
    /** De koppeling met Wikidata klopt niet: weghalen en niet opnieuw automatisch koppelen. */
    fun reject(done: (String) -> Unit) {
        val id = authorId.value ?: return
        viewModelScope.launch {
            authors.reject(id)
            done("Losgekoppeld van Wikidata")
        }
    }

    fun lookUp(done: (String) -> Unit) {
        val id = authorId.value ?: return
        lookingUp.value = true
        viewModelScope.launch {
            val ok = authors.lookUpAgain(id)
            lookingUp.value = false
            done(if (ok) "Bijgewerkt vanuit Wikidata" else "Wikidata is nu niet bereikbaar")
        }
    }
}
