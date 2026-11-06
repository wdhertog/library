package nl.wdhertog.library.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

/** Een uitlening met de gegevens die de lijsten nodig hebben. */
data class LoanRow(
    val loanId: Long,
    val bookId: Long,
    val title: String,
    val isbn13: String?,
    /** Eigen foto (bestandsnaam) of URL van een bron; zie CoverStore.model. */
    val coverUrl: String?,
    val personName: String?,
    val lentAt: LocalDate?,
    val dueAt: LocalDate?,
    val returnedAt: LocalDate?,
    val note: String?,
)

/** Een boek dat je zelf van iemand hebt geleend. */
data class BorrowedRow(
    val bookId: Long,
    val title: String,
    val isbn13: String?,
    val coverUrl: String?,
    val personName: String?,
    val dueAt: LocalDate?,
)

@Dao
interface LoanDao {

    @Query(
        """
        SELECT l.id AS loanId, l.bookId, b.title, b.isbn13, COALESCE(b.coverFile, b.coverUrl) AS coverUrl, p.name AS personName,
               l.lentAt, l.dueAt, l.returnedAt, l.note
        FROM loan l JOIN book b ON b.id = l.bookId LEFT JOIN person p ON p.id = l.personId
        WHERE l.returnedAt IS NULL
        ORDER BY l.dueAt IS NULL, l.dueAt, l.lentAt
        """
    )
    fun observeActiveLoans(): Flow<List<LoanRow>>

    @Query(
        """
        SELECT l.id AS loanId, l.bookId, b.title, b.isbn13, COALESCE(b.coverFile, b.coverUrl) AS coverUrl, p.name AS personName,
               l.lentAt, l.dueAt, l.returnedAt, l.note
        FROM loan l JOIN book b ON b.id = l.bookId LEFT JOIN person p ON p.id = l.personId
        WHERE l.returnedAt IS NOT NULL
        ORDER BY l.returnedAt DESC
        LIMIT 200
        """
    )
    fun observeReturnedLoans(): Flow<List<LoanRow>>

    @Query(
        """
        SELECT l.id AS loanId, l.bookId, b.title, b.isbn13, COALESCE(b.coverFile, b.coverUrl) AS coverUrl, p.name AS personName,
               l.lentAt, l.dueAt, l.returnedAt, l.note
        FROM loan l JOIN book b ON b.id = l.bookId LEFT JOIN person p ON p.id = l.personId
        WHERE l.bookId = :bookId
        ORDER BY l.returnedAt IS NOT NULL, l.lentAt DESC
        """
    )
    fun observeLoansForBook(bookId: Long): Flow<List<LoanRow>>

    @Query(
        """
        SELECT b.id AS bookId, b.title, b.isbn13, COALESCE(b.coverFile, b.coverUrl) AS coverUrl, p.name AS personName, b.borrowedDueDate AS dueAt
        FROM book b LEFT JOIN person p ON p.id = b.borrowedFromPersonId
        WHERE b.ownership = 'BORROWED'
        ORDER BY b.borrowedDueDate IS NULL, b.borrowedDueDate
        """
    )
    fun observeBorrowedBooks(): Flow<List<BorrowedRow>>

    /** Voor herinneringen: uitleningen en geleende boeken die uiterlijk op [date] terug moeten. */
    @Query(
        """
        SELECT l.id AS loanId, l.bookId, b.title, b.isbn13, COALESCE(b.coverFile, b.coverUrl) AS coverUrl, p.name AS personName,
               l.lentAt, l.dueAt, l.returnedAt, l.note
        FROM loan l JOIN book b ON b.id = l.bookId LEFT JOIN person p ON p.id = l.personId
        WHERE l.returnedAt IS NULL AND l.dueAt IS NOT NULL AND l.dueAt <= :date
        """
    )
    suspend fun loansDueBy(date: LocalDate): List<LoanRow>

    @Query(
        """
        SELECT b.id AS bookId, b.title, b.isbn13, COALESCE(b.coverFile, b.coverUrl) AS coverUrl, p.name AS personName, b.borrowedDueDate AS dueAt
        FROM book b LEFT JOIN person p ON p.id = b.borrowedFromPersonId
        WHERE b.ownership = 'BORROWED' AND b.borrowedDueDate IS NOT NULL AND b.borrowedDueDate <= :date
        """
    )
    suspend fun borrowedDueBy(date: LocalDate): List<BorrowedRow>

    @Query("SELECT COUNT(*) FROM loan WHERE returnedAt IS NULL AND dueAt IS NOT NULL AND dueAt < :today")
    fun observeOverdueLoanCount(today: LocalDate): Flow<Int>

    @Query("SELECT COUNT(*) FROM book WHERE ownership = 'BORROWED' AND borrowedDueDate IS NOT NULL AND borrowedDueDate < :today")
    fun observeOverdueBorrowedCount(today: LocalDate): Flow<Int>

    @Query("SELECT COUNT(*) FROM loan WHERE bookId = :bookId AND returnedAt IS NULL")
    suspend fun activeLoanCount(bookId: Long): Int

    /** Wanneer elke uitlening begon (leeg = onbekend), voor de badge Uitlener. */
    @Query("SELECT lentAt FROM loan")
    fun observeLentDates(): Flow<List<LocalDate?>>

    @Query("SELECT COUNT(*) FROM loan WHERE bookId = :bookId")
    suspend fun loanCount(bookId: Long): Int

    @Insert
    suspend fun insertLoan(loan: Loan): Long

    @Query("UPDATE loan SET returnedAt = :date, updatedAt = :now WHERE id = :loanId")
    suspend fun markReturned(loanId: Long, date: LocalDate, now: Instant)

    @Query("UPDATE loan SET dueAt = :dueAt, updatedAt = :now WHERE id = :loanId")
    suspend fun setDueDate(loanId: Long, dueAt: LocalDate?, now: Instant)

    @Query(
        """
        UPDATE book SET ownership = :ownership, borrowedFromPersonId = :personId, borrowedDueDate = :dueAt, updatedAt = :now
        WHERE id = :bookId
        """
    )
    suspend fun setOwnership(bookId: Long, ownership: Ownership, personId: Long?, dueAt: LocalDate?, now: Instant)

    @Query(
        """
        UPDATE book SET ownership = 'NOT_OWNED', borrowedFromPersonId = NULL, borrowedDueDate = NULL,
               disposalReason = :reason, disposedTo = :to, disposedAt = :date, updatedAt = :now
        WHERE id = :bookId
        """
    )
    suspend fun setDisposed(bookId: Long, reason: DisposalReason?, to: String?, date: LocalDate?, now: Instant)

    @Query("UPDATE book SET disposalReason = NULL, disposedTo = NULL, disposedAt = NULL WHERE id = :bookId")
    suspend fun clearDisposal(bookId: Long)

    @Query("DELETE FROM loan WHERE id = :loanId")
    suspend fun deleteLoan(loanId: Long)

    // --- Personen ---

    /** Namen die nergens meer gebruikt worden, verdwijnen ook uit de suggesties. */
    @Query(
        """
        DELETE FROM person
        WHERE id NOT IN (SELECT personId FROM loan WHERE personId IS NOT NULL)
          AND id NOT IN (SELECT borrowedFromPersonId FROM book WHERE borrowedFromPersonId IS NOT NULL)
        """
    )
    suspend fun deleteUnusedPersons()

    @Query("SELECT * FROM person WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findPerson(name: String): Person?

    @Insert
    suspend fun insertPerson(person: Person): Long

    @Query("SELECT name FROM person ORDER BY name COLLATE NOCASE")
    fun observePersonNames(): Flow<List<String>>
}
