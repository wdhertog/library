package nl.wdhertog.library.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class BookAuthorRow(val bookId: Long, val name: String)

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

    @Query("SELECT id FROM book WHERE isbn13 = :isbn13 AND id != :excludeId LIMIT 1")
    suspend fun findBookIdByIsbn(isbn13: String, excludeId: Long): Long?

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
