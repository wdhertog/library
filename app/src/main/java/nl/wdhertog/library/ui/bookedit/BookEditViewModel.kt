package nl.wdhertog.library.ui.bookedit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.wdhertog.library.data.repository.BookDraft
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.lookup.EnrichScheduler
import nl.wdhertog.library.scan.Isbn
import nl.wdhertog.library.ui.navigation.BookEditRoute
import java.time.Year

data class BookEditUiState(
    val loading: Boolean = true,
    val draft: BookDraft = BookDraft(),
    /** Fouten worden pas getoond na de eerste poging tot opslaan. */
    val showErrors: Boolean = false,
    val duplicateBookId: Long? = null,
    val saving: Boolean = false,
    val savedBookId: Long? = null,
) {
    val titleError: String? get() = if (draft.title.isBlank()) "Titel is verplicht" else null

    val isbnError: String?
        get() = if (draft.isbn.isNotBlank() && Isbn.toIsbn13(draft.isbn) == null) "Geen geldig ISBN" else null

    val yearError: String?
        get() {
            val text = draft.publishedYear.trim()
            if (text.isEmpty()) return null
            val year = text.toIntOrNull()
            return if (year == null || year !in 0..Year.now().value + 1) "Ongeldig jaar" else null
        }

    val pagesError: String?
        get() = if (draft.pages.isNotBlank() && (draft.pages.trim().toIntOrNull() ?: 0) <= 0) "Ongeldig aantal" else null

    val isValid: Boolean get() = listOf(titleError, isbnError, yearError, pagesError).all { it == null }
}

@OptIn(FlowPreview::class)
class BookEditViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: BookRepository,
    private val scheduler: EnrichScheduler,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<BookEditRoute>()
    val bookId = route.bookId
    val isNew = bookId == 0L

    private val _state = MutableStateFlow(
        BookEditUiState(
            loading = !isNew,
            draft = BookDraft(title = route.title, authors = listOf(route.author)),
        ),
    )
    val state: StateFlow<BookEditUiState> = _state.asStateFlow()

    val authorSuggestions = repository.observeAuthorNames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val seriesSuggestions = repository.observeSeriesNames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (!isNew) {
            viewModelScope.launch {
                val draft = repository.loadDraft(bookId) ?: BookDraft()
                _state.update { it.copy(loading = false, draft = draft) }
            }
        }
        viewModelScope.launch {
            _state.map { it.draft.isbn }
                .distinctUntilChanged()
                .debounce(300)
                .collect { isbn ->
                    val duplicate = repository.findDuplicateIsbn(isbn, excludeBookId = bookId)
                    _state.update { it.copy(duplicateBookId = duplicate) }
                }
        }
    }

    fun update(transform: (BookDraft) -> BookDraft) {
        _state.update { it.copy(draft = transform(it.draft)) }
    }

    fun save() {
        val current = _state.value
        if (current.saving) return
        if (!current.isValid) {
            _state.update { it.copy(showErrors = true) }
            return
        }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val id = repository.save(bookId, current.draft)
            if (isNew && route.coverFile.isNotBlank()) repository.setCoverFile(id, route.coverFile)
            // Een nieuw boek met ISBN kan op de achtergrond worden aangevuld (cover, serie, …).
            if (current.draft.isbn.isNotBlank()) scheduler.schedule()
            _state.update { it.copy(saving = false, savedBookId = id) }
        }
    }

    /** Niet opgeslagen: de alvast bewaarde cover-foto opruimen. */
    override fun onCleared() {
        if (isNew && route.coverFile.isNotBlank() && _state.value.savedBookId == null) {
            repository.discardCoverFile(route.coverFile)
        }
    }
}
