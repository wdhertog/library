package nl.wdhertog.library.ui.bookdetail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import nl.wdhertog.library.data.db.ReadingStatus
import nl.wdhertog.library.data.repository.BadgeService
import nl.wdhertog.library.data.db.BorrowedRow
import nl.wdhertog.library.data.db.DisposalReason
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.db.SessionRow
import nl.wdhertog.library.data.repository.ReadingRepository
import nl.wdhertog.library.data.db.LoanRow
import nl.wdhertog.library.data.db.LocationType
import nl.wdhertog.library.data.repository.LoanRepository
import java.time.LocalDate
import nl.wdhertog.library.data.repository.LocationNode
import nl.wdhertog.library.data.repository.LocationRepository
import nl.wdhertog.library.lookup.EnrichmentService
import nl.wdhertog.library.lookup.LookupException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.wdhertog.library.data.repository.BookDetail
import nl.wdhertog.library.data.repository.BookListItem
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.ui.navigation.BookDetailRoute

sealed interface BookDetailUiState {
    data object Loading : BookDetailUiState
    data object NotFound : BookDetailUiState
    data class Loaded(val detail: BookDetail) : BookDetailUiState
}

class BookDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: BookRepository,
    private val enrichment: EnrichmentService,
    private val locations: LocationRepository,
    private val loans: LoanRepository,
    private val reading: ReadingRepository,
    private val badges: BadgeService? = null,
    private val coverSource: nl.wdhertog.library.lookup.OpenLibraryCovers? = null,
    private val genreService: nl.wdhertog.library.data.repository.GenreService? = null,
) : ViewModel() {

    /** Genres van het werk zelf kiezen. */
    fun setGenres(genres: List<String>) {
        val workId = (state.value as? BookDetailUiState.Loaded)?.detail?.works?.firstOrNull()?.id ?: return
        viewModelScope.launch { genreService?.setGenres(workId, genres) }
    }

    /** "Andere cover kiezen…": null = dicht, lege lijst na laden = niets gevonden. */
    sealed interface CoverChoice {
        data object Loading : CoverChoice
        data class Loaded(val candidates: List<nl.wdhertog.library.lookup.CoverCandidate>, val byTitle: Boolean = false) : CoverChoice
        data class Failed(val message: String) : CoverChoice
    }

    val coverChoice = kotlinx.coroutines.flow.MutableStateFlow<CoverChoice?>(null)

    fun openCoverChoice() {
        val detail = (state.value as? BookDetailUiState.Loaded)?.detail ?: return
        val source = coverSource ?: return
        coverChoice.value = CoverChoice.Loading
        viewModelScope.launch {
            coverChoice.value = try {
                val choices = source.candidates(
                    detail.book.isbn13, detail.works.firstOrNull()?.openLibraryWorkId, detail.book.language,
                    otherIsbns = detail.otherEditions.mapNotNull { it.isbn13 },
                    titles = listOfNotNull(detail.book.title, detail.works.firstOrNull()?.originalTitle),
                    author = detail.authors.firstOrNull(),
                )
                CoverChoice.Loaded(choices.candidates, choices.byTitle)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                CoverChoice.Failed("Open Library is nu niet bereikbaar")
            }
        }
    }

    fun closeCoverChoice() {
        coverChoice.value = null
    }

    fun chooseCover(candidate: nl.wdhertog.library.lookup.CoverCandidate) {
        coverChoice.value = null
        viewModelScope.launch {
            repository.chooseCover(bookId, candidate.url)
            _messages.send("Cover gekozen; opnieuw ophalen zet hem niet terug")
        }
    }

    val bookId = savedStateHandle.toRoute<BookDetailRoute>().bookId

    /** Huidige en eerdere uitleningen van dit boek (huidige eerst). */
    val bookLoans: StateFlow<List<LoanRow>> = loans.observeLoansForBook(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Gevuld als dit boek van iemand anders geleend is. */
    val borrowed: StateFlow<BorrowedRow?> = loans.observeBorrowedBooks()
        .map { rows -> rows.firstOrNull { it.bookId == bookId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val personNames: StateFlow<List<String>> = loans.observePersonNames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun lend(person: String, dueAt: LocalDate?, note: String?) {
        viewModelScope.launch { loans.lend(bookId, person, dueAt, note) }
    }

    fun markReturned(loanId: Long) {
        viewModelScope.launch { loans.markReturned(loanId) }
    }

    fun markBorrowed(person: String, dueAt: LocalDate?) {
        viewModelScope.launch { loans.markBorrowed(bookId, person, dueAt) }
    }

    fun markBorrowedReturned(keep: Boolean, onDeleted: () -> Unit) {
        viewModelScope.launch {
            loans.markBorrowedReturned(bookId, keep)
            if (!keep) onDeleted()
        }
    }

    fun deleteLoan(loanId: Long) {
        viewModelScope.launch { loans.deleteLoan(loanId) }
    }

    /** Leessessies van dit werk, in welke editie dan ook. */
    val sessions: StateFlow<List<SessionRow>> = reading.observeSessionsForWork(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun startReading() {
        viewModelScope.launch { reading.start(bookId) }
    }

    fun saveSession(session: ReadingSession) {
        viewModelScope.launch {
            // Uitgelezen: kijken of dat een nieuwe badge oplevert, en dat even melden.
            val before = if (session.status == ReadingStatus.FINISHED) badges?.current() else null
            reading.save(session)
            if (before != null) {
                val earned = BadgeService.newlyEarned(before, badges?.current().orEmpty())
                if (earned.isNotEmpty()) {
                    _messages.send("Nieuwe badge: " + earned.joinToString(", ") { it.title } + " 🎉")
                }
            }
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch { reading.delete(sessionId) }
    }

    fun updateProgress(sessionId: Long, page: Int?, percent: Int?) {
        viewModelScope.launch { reading.updateProgress(sessionId, page, percent) }
    }

    fun toggleWantToRead() {
        val book = (state.value as? BookDetailUiState.Loaded)?.detail?.book ?: return
        viewModelScope.launch { reading.setWantToRead(bookId, !book.wantToRead) }
    }

    /** Voor "koppelen aan andere editie": alle andere boeken. */
    val otherBooks: StateFlow<List<BookListItem>> = repository.observeLibrary()
        .map { items -> items.filter { it.book.id != bookId } }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setPrimaryEdition() {
        viewModelScope.launch {
            repository.setPrimaryEdition(bookId)
            _messages.send("Hoofdeditie: deze cover en titel in series en op de auteurspagina")
        }
    }

    fun linkAsSameWork(otherBookId: Long) {
        viewModelScope.launch {
            repository.linkAsSameWork(bookId, otherBookId)
            _messages.send("Gekoppeld als editie van hetzelfde werk")
        }
    }

    fun splitFromWork() {
        viewModelScope.launch {
            repository.splitFromWork(bookId)
            _messages.send("Losgekoppeld: dit boek is nu een eigen werk")
        }
    }

    fun markDisposed(reason: DisposalReason, to: String?, date: LocalDate?) {
        viewModelScope.launch { loans.markDisposed(bookId, reason, to, date) }
    }

    fun toggleFavorite() {
        val book = (state.value as? BookDetailUiState.Loaded)?.detail?.book ?: return
        viewModelScope.launch { repository.setFavorite(bookId, !book.favorite) }
    }

    fun setOwner(owner: String?) {
        viewModelScope.launch { repository.setOwner(bookId, owner) }
    }

    val ownerNames: StateFlow<List<String>> = repository.observeOwnerNames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun markOwned() {
        viewModelScope.launch { loans.markOwned(bookId) }
    }

    val locationTree: StateFlow<List<LocationNode>> = locations.observeTree()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Zet het boek achteraan op de gekozen plek, of haalt het van zijn plek (null). */
    fun moveTo(locationId: Long?) {
        viewModelScope.launch { locations.moveBook(bookId, locationId) }
    }

    suspend fun createLocation(name: String, type: LocationType, parentId: Long?): Long =
        locations.add(name, type, parentId)

    val refreshing = MutableStateFlow(false)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** "Gegevens opnieuw ophalen": overschrijft alles behalve wat je zelf hebt ingevuld. */
    fun refresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            val message = try {
                if (enrichment.enrich(bookId, refresh = true)) "Gegevens bijgewerkt" else "Niet gevonden in de bronnen"
            } catch (_: LookupException) {
                "Bronnen niet bereikbaar; controleer de internetverbinding"
            } finally {
                refreshing.value = false
            }
            _messages.send(message)
        }
    }

    val state: StateFlow<BookDetailUiState> = repository.observeDetail(bookId)
        .map { detail -> detail?.let(BookDetailUiState::Loaded) ?: BookDetailUiState.NotFound }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDetailUiState.Loading)

    /** Eigen foto weghalen; een cover uit een bron (als die er is) komt dan weer terug. */
    fun removeCoverPhoto() {
        viewModelScope.launch { repository.setCoverFile(bookId, null) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.delete(bookId)
            onDeleted()
        }
    }
}
