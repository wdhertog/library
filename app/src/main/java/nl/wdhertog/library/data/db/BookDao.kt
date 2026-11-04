package nl.wdhertog.library.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class BookAuthorRow(val bookId: Long, val name: String)

/** Het (eerste) werk van een boek; edities van hetzelfde werk delen dit id. */
data class BookWorkRow(val bookId: Long, val workId: Long)

data class BookSeriesRow(
    val bookId: Long,
    val seriesId: Long,
    val seriesName: String,
    val seriesIndex: String?,
)

@Dao
interface BookDao {

    // --- Boeken ---

    @Query("SELECT * FROM book")
    fun observeBooks(): Flow<List<Book>>

    @Query("SELECT * FROM book WHERE id = :id")
    fun observeBook(id: Long): Flow<Book?>

    @Query("SELECT * FROM book WHERE id = :id")
    suspend fun getBook(id: Long): Book?

    @Query("SELECT * FROM book")
    suspend fun getBooks(): List<Book>

    @Query("SELECT id FROM book WHERE isbn13 = :isbn13 AND id != :excludeId LIMIT 1")
    suspend fun findBookIdByIsbn(isbn13: String, excludeId: Long): Long?

    @Query("SELECT * FROM book WHERE isbn13 = :isbn13 LIMIT 1")
    suspend fun findBookByIsbn(isbn13: String): Book?

    @Query(
        """
        SELECT id FROM book
        WHERE isbn13 IS NOT NULL AND enrichStatus IN ('NEW', 'QUICK')
        ORDER BY addedAt LIMIT :limit
        """
    )
    suspend fun booksAwaitingEnrichment(limit: Int): List<Long>

    @Query("SELECT COUNT(*) FROM book WHERE isbn13 IS NOT NULL AND enrichStatus IN ('NEW', 'QUICK')")
    fun observeAwaitingEnrichmentCount(): Flow<Int>

    /** Zet mislukte boeken terug in de wachtrij; geeft het aantal terug. */
    @Query("UPDATE book SET enrichStatus = 'NEW' WHERE enrichStatus = 'FAILED' AND isbn13 IS NOT NULL")
    suspend fun requeueFailed(): Int

    @Query("UPDATE book SET enrichStatus = :status, updatedAt = :now WHERE id = :id")
    suspend fun setEnrichStatus(id: Long, status: EnrichStatus, now: java.time.Instant)

    /** Nu bekend: de delen opnieuw ophalen (partsFetchedAt leeg). */
    @Query("UPDATE series SET wikidataId = :wikidataId, partsFetchedAt = NULL, updatedAt = :now WHERE id = :id AND wikidataId IS NULL")
    suspend fun setSeriesWikidataIfMissing(id: Long, wikidataId: String, now: java.time.Instant)

    @Query("UPDATE book SET coverFile = :coverFile, updatedAt = :now WHERE id = :id")
    suspend fun setCoverFile(id: Long, coverFile: String?, now: java.time.Instant)

    @Query("UPDATE book SET favorite = :favorite, updatedAt = :now WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean, now: java.time.Instant)

    @Query("UPDATE book SET owner = :owner, updatedAt = :now WHERE id = :id")
    suspend fun setOwner(id: Long, owner: String?, now: java.time.Instant)

    @Query("SELECT DISTINCT owner FROM book WHERE owner IS NOT NULL ORDER BY owner COLLATE NOCASE")
    fun observeOwnerNames(): Flow<List<String>>

    // --- Meerdere boeken tegelijk ---

    @Query("UPDATE book SET owner = :owner, updatedAt = :now WHERE id IN (:ids)")
    suspend fun setOwnerFor(ids: List<Long>, owner: String?, now: java.time.Instant)

    @Query("UPDATE book SET favorite = :favorite, updatedAt = :now WHERE id IN (:ids)")
    suspend fun setFavoriteFor(ids: List<Long>, favorite: Boolean, now: java.time.Instant)

    @Query("UPDATE book SET wantToRead = :wantToRead, updatedAt = :now WHERE id IN (:ids)")
    suspend fun setWantToReadFor(ids: List<Long>, wantToRead: Boolean, now: java.time.Instant)

    @Query("SELECT COUNT(*) FROM book")
    suspend fun countBooks(): Int

    @Insert
    suspend fun insertBook(book: Book): Long

    @Update
    suspend fun updateBook(book: Book)

    @Delete
    suspend fun deleteBook(book: Book)

    // --- Auteurs en series per boek (voor lijst en detail) ---

    /** Auteurs van alle boeken, in de volgorde werk → auteur. */
    @Query(
        """
        SELECT bw.bookId AS bookId, a.name AS name
        FROM book_work bw
        JOIN work_author wa ON wa.workId = bw.workId
        JOIN author a ON a.id = wa.authorId
        ORDER BY bw.bookId, bw.position, wa.position
        """
    )
    fun observeBookAuthors(): Flow<List<BookAuthorRow>>

    @Query(
        """
        SELECT bw.bookId AS bookId, a.name AS name
        FROM book_work bw
        JOIN work_author wa ON wa.workId = bw.workId
        JOIN author a ON a.id = wa.authorId
        WHERE bw.bookId = :bookId
        ORDER BY bw.position, wa.position
        """
    )
    fun observeAuthorsForBook(bookId: Long): Flow<List<BookAuthorRow>>

    /** Serie van het eerste werk in elk boek. */
    @Query(
        """
        SELECT bw.bookId AS bookId, s.id AS seriesId, s.name AS seriesName, w.seriesIndex AS seriesIndex
        FROM book_work bw
        JOIN work w ON w.id = bw.workId
        JOIN series s ON s.id = w.seriesId
        WHERE bw.position = 0
        """
    )
    fun observeBookSeries(): Flow<List<BookSeriesRow>>

    @Query(
        """
        SELECT bw.bookId AS bookId, s.id AS seriesId, s.name AS seriesName, w.seriesIndex AS seriesIndex
        FROM book_work bw
        JOIN work w ON w.id = bw.workId
        JOIN series s ON s.id = w.seriesId
        WHERE bw.bookId = :bookId
        ORDER BY bw.position
        """
    )
    fun observeSeriesForBook(bookId: Long): Flow<List<BookSeriesRow>>

    @Query(
        """
        SELECT w.* FROM work w
        JOIN book_work bw ON bw.workId = w.id
        WHERE bw.bookId = :bookId
        ORDER BY bw.position
        """
    )
    fun observeWorksForBook(bookId: Long): Flow<List<Work>>

    /** Andere exemplaren in de collectie die (deels) hetzelfde werk bevatten. */
    @Query(
        """
        SELECT DISTINCT b.* FROM book_work mine
        JOIN book_work other ON other.workId = mine.workId AND other.bookId != mine.bookId
        JOIN book b ON b.id = other.bookId
        WHERE mine.bookId = :bookId
        ORDER BY b.language, b.title
        """
    )
    fun observeOtherEditions(bookId: Long): Flow<List<Book>>

    // --- Werken ---

    @Query(
        """
        SELECT w.* FROM work w
        JOIN book_work bw ON bw.workId = w.id
        WHERE bw.bookId = :bookId
        ORDER BY bw.position
        LIMIT 1
        """
    )
    suspend fun getPrimaryWork(bookId: Long): Work?

    @Query(
        """
        SELECT a.name FROM author a
        JOIN work_author wa ON wa.authorId = a.id
        WHERE wa.workId = :workId
        ORDER BY wa.position
        """
    )
    suspend fun getAuthorNames(workId: Long): List<String>

    @Insert
    suspend fun insertWork(work: Work): Long

    @Update
    suspend fun updateWork(work: Work)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookWork(bookWork: BookWork)

    @Query("DELETE FROM book_work WHERE bookId = :bookId AND workId = :workId")
    suspend fun deleteBookWork(bookId: Long, workId: Long)

    @Query("SELECT bookId, workId FROM book_work WHERE position = 0")
    fun observePrimaryWorks(): Flow<List<BookWorkRow>>

    @Query("SELECT * FROM work WHERE id = :id")
    suspend fun getWork(id: Long): Work?

    @Query("SELECT bookId FROM book_work WHERE workId = :workId")
    suspend fun bookIdsOfWork(workId: Long): List<Long>

    /** Alle boeken van werk [from] naar werk [into]; een boek dat al bij [into] hoort, houdt die koppeling. */
    @Query("UPDATE OR IGNORE book_work SET workId = :into WHERE workId = :from")
    suspend fun moveBookWorks(from: Long, into: Long)

    @Query("DELETE FROM book_work WHERE workId = :workId")
    suspend fun deleteBookWorksOf(workId: Long)

    @Query("UPDATE book_work SET workId = :newWorkId WHERE bookId = :bookId AND workId = :oldWorkId")
    suspend fun replaceBookWork(bookId: Long, oldWorkId: Long, newWorkId: Long)

    @Query("SELECT COUNT(*) FROM book_work WHERE workId = :workId")
    suspend fun countBooksForWork(workId: Long): Int

    @Query("SELECT * FROM work WHERE wikidataId = :wikidataId AND id != :excludeId LIMIT 1")
    suspend fun findWorkByWikidataId(wikidataId: String, excludeId: Long): Work?

    @Query("SELECT * FROM work WHERE openLibraryWorkId = :olId AND id != :excludeId LIMIT 1")
    suspend fun findWorkByOpenLibraryId(olId: String, excludeId: Long): Work?

    @Query("DELETE FROM work WHERE id NOT IN (SELECT workId FROM book_work)")
    suspend fun deleteOrphanWorks()

    // --- Auteurs ---

    @Query("SELECT * FROM author WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findAuthor(name: String): Author?

    @Insert
    suspend fun insertAuthor(author: Author): Long

    @Query("DELETE FROM work_author WHERE workId = :workId")
    suspend fun clearWorkAuthors(workId: Long)

    @Insert
    suspend fun insertWorkAuthors(links: List<WorkAuthor>)

    @Query("DELETE FROM author WHERE id NOT IN (SELECT authorId FROM work_author)")
    suspend fun deleteOrphanAuthors()

    @Query("SELECT name FROM author ORDER BY name COLLATE NOCASE")
    fun observeAuthorNames(): Flow<List<String>>

    // --- Series ---

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun getSeries(id: Long): Series?

    @Query("SELECT * FROM series WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findSeries(name: String): Series?

    @Insert
    suspend fun insertSeries(series: Series): Long

    @Query("DELETE FROM series WHERE id NOT IN (SELECT seriesId FROM work WHERE seriesId IS NOT NULL)")
    suspend fun deleteOrphanSeries()

    @Query("SELECT name FROM series ORDER BY name COLLATE NOCASE")
    fun observeSeriesNames(): Flow<List<String>>
}
