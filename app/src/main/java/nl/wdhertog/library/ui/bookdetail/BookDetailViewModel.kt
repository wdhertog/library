package nl.wdhertog.library.ui.bookdetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import kotlinx.coroutines.flow.SharingStarted
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
) : ViewModel() {

    val bookId = savedStateHandle.toRoute<BookDetailRoute>().bookId

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
