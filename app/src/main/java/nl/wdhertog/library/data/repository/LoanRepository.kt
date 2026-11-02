package nl.wdhertog.library.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import nl.wdhertog.library.data.db.BorrowedRow
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.data.db.Loan
import nl.wdhertog.library.data.db.LoanRow
import nl.wdhertog.library.data.db.Ownership
import nl.wdhertog.library.data.db.Person
import java.time.Instant
import java.time.LocalDate

class LoanRepository(private val db: LibraryDatabase, private val books: BookRepository) {

    private val dao = db.loanDao()

    fun observeActiveLoans(): Flow<List<LoanRow>> = dao.observeActiveLoans()

    fun observeReturnedLoans(): Flow<List<LoanRow>> = dao.observeReturnedLoans()

    fun observeBorrowedBooks(): Flow<List<BorrowedRow>> = dao.observeBorrowedBooks()

    fun observeLoansForBook(bookId: Long): Flow<List<LoanRow>> = dao.observeLoansForBook(bookId)

    fun observePersonNames(): Flow<List<String>> = dao.observePersonNames()

    /** Aantal te late uitleningen plus te laat terug te brengen boeken, voor het badge in de menubalk. */
    fun observeOverdueCount(today: LocalDate = LocalDate.now()): Flow<Int> =
        combine(dao.observeOverdueLoanCount(today), dao.observeOverdueBorrowedCount(today)) { a, b -> a + b }

    suspend fun loansDueBy(date: LocalDate) = dao.loansDueBy(date)

    suspend fun borrowedDueBy(date: LocalDate) = dao.borrowedDueBy(date)

    /** Leent een boek uit. Geeft false als het al uitgeleend is. */
    suspend fun lend(bookId: Long, personName: String, dueAt: LocalDate?, note: String?, lentAt: LocalDate = LocalDate.now()): Boolean =
        db.withTransaction {
            if (dao.activeLoanCount(bookId) > 0) return@withTransaction false
            dao.insertLoan(
                Loan(
                    bookId = bookId,
                    personId = personId(personName),
                    lentAt = lentAt,
                    dueAt = dueAt,
                    note = note?.trim()?.ifEmpty { null },
                ),
            )
            true
        }

    suspend fun markReturned(loanId: Long, date: LocalDate = LocalDate.now()) = dao.markReturned(loanId, date, Instant.now())

    suspend fun setLoanDueDate(loanId: Long, dueAt: LocalDate?) = dao.setDueDate(loanId, dueAt, Instant.now())

    /** Markeert een boek als geleend van iemand anders. */
    suspend fun markBorrowed(bookId: Long, personName: String, dueAt: LocalDate?) = db.withTransaction {
        dao.setOwnership(bookId, Ownership.BORROWED, personId(personName), dueAt, Instant.now())
    }

    /**
     * Geleend boek teruggebracht. Met [keep] blijft het in de bibliotheek als "niet (meer) in bezit",
     * zodat de leesgeschiedenis bewaard blijft; anders wordt het verwijderd.
     */
    suspend fun markBorrowedReturned(bookId: Long, keep: Boolean) {
        if (keep) dao.setOwnership(bookId, Ownership.NOT_OWNED, null, null, Instant.now())
        else books.delete(bookId)
    }

    /** Toch eigen boek (bijv. per ongeluk als geleend gemarkeerd, of gekocht). */
    suspend fun markOwned(bookId: Long) = dao.setOwnership(bookId, Ownership.OWNED, null, null, Instant.now())

    private suspend fun personId(name: String): Long? {
        val trimmed = name.trim().ifEmpty { return null }
        return dao.findPerson(trimmed)?.id ?: dao.insertPerson(Person(name = trimmed))
    }
}
