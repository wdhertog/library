package nl.wdhertog.library.ui.loans

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.wdhertog.library.data.db.BorrowedRow
import nl.wdhertog.library.data.db.LoanRow
import nl.wdhertog.library.data.db.Ownership
import nl.wdhertog.library.data.repository.BookListItem
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.data.repository.LoanRepository
import java.time.LocalDate

data class LoansUiState(
    val loaded: Boolean = false,
    val active: List<LoanRow> = emptyList(),
    val returned: List<LoanRow> = emptyList(),
    val borrowed: List<BorrowedRow> = emptyList(),
    /** Eigen boeken die nu niet uitgeleend zijn: uit te lenen, of te markeren als geleend (bijv. net gescand). */
    val available: List<BookListItem> = emptyList(),
)

class LoansViewModel(
    private val loans: LoanRepository,
    books: BookRepository,
) : ViewModel() {

    val state: StateFlow<LoansUiState> = combine(
        loans.observeActiveLoans(),
        loans.observeReturnedLoans(),
        loans.observeBorrowedBooks(),
        books.observeLibrary(),
    ) { active, returned, borrowed, library ->
        val lentOut = active.map { it.bookId }.toSet()
        LoansUiState(
            loaded = true,
            active = active,
            returned = returned,
            borrowed = borrowed,
            available = library.filter { it.book.ownership == Ownership.OWNED && it.book.id !in lentOut },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoansUiState())

    val personNames: StateFlow<List<String>> = loans.observePersonNames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun lend(bookId: Long, person: String, dueAt: LocalDate?, note: String?) {
        viewModelScope.launch { loans.lend(bookId, person, dueAt, note) }
    }

    fun markReturned(loanId: Long) {
        viewModelScope.launch { loans.markReturned(loanId) }
    }

    fun markBorrowed(bookId: Long, person: String, dueAt: LocalDate?) {
        viewModelScope.launch { loans.markBorrowed(bookId, person, dueAt) }
    }

    fun markBorrowedReturned(bookId: Long, keep: Boolean) {
        viewModelScope.launch { loans.markBorrowedReturned(bookId, keep) }
    }

    fun markOwned(bookId: Long) {
        viewModelScope.launch { loans.markOwned(bookId) }
    }
}
