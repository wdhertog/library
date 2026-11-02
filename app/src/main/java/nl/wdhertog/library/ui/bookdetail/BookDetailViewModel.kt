package nl.wdhertog.library.ui.bookdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import nl.wdhertog.library.data.db.BorrowedRow
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
) : ViewModel() {

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

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.delete(bookId)
            onDeleted()
        }
    }
}
