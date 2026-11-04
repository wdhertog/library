package nl.wdhertog.library.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Een auteur in de lijst Auteurs. */
data class AuthorSummary(val id: Long, val name: String, val books: Int, val works: Int)

/** Welk boek bij welk werk hoort, voor de boeken van een auteur. */
data class BookWorkLink(val bookId: Long, val workId: Long)

@Dao
interface AuthorDao {

    /** Auteurs met minstens één boek. */
    @Query(
        """
        SELECT a.id, a.name, COUNT(DISTINCT bw.bookId) AS books, COUNT(DISTINCT wa.workId) AS works
        FROM author a
        JOIN work_author wa ON wa.authorId = a.id
        JOIN book_work bw ON bw.workId = wa.workId
        GROUP BY a.id
        """
    )
    fun observeSummaries(): Flow<List<AuthorSummary>>

    @Query("SELECT * FROM author WHERE id = :id")
    fun observeAuthor(id: Long): Flow<Author?>

    @Query("SELECT * FROM author WHERE id = :id")
    suspend fun get(id: Long): Author?

    @Query("SELECT id FROM author WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findId(name: String): Long?

    @Query(
        """
        SELECT DISTINCT bw.bookId, bw.workId FROM book_work bw
        JOIN work_author wa ON wa.workId = bw.workId
        WHERE wa.authorId = :authorId
        """
    )
    fun observeBookWorks(authorId: Long): Flow<List<BookWorkLink>>

    @Query(
        """
        SELECT DISTINCT w.wikidataId FROM work w JOIN work_author wa ON wa.workId = w.id
        WHERE wa.authorId = :authorId AND w.wikidataId IS NOT NULL
        """
    )
    suspend fun workWikidataIds(authorId: Long): List<String>

    /** Gemiddelde van je eigen beoordelingen voor boeken van deze auteur. */
    @Query(
        """
        SELECT AVG(rs.rating) FROM reading_session rs
        WHERE rs.rating IS NOT NULL AND rs.bookId IN (
            SELECT bw.bookId FROM book_work bw JOIN work_author wa ON wa.workId = bw.workId WHERE wa.authorId = :authorId
        )
        """
    )
    fun observeAverageRating(authorId: Long): Flow<Double?>

    @Query(
        """
        UPDATE author SET wikidataId = :wikidataId, birthYear = :birthYear, deathYear = :deathYear,
               country = :country, description = :description, infoFetchedAt = :at
        WHERE id = :id
        """
    )
    suspend fun setInfo(id: Long, wikidataId: String?, birthYear: Int?, deathYear: Int?, country: String?, description: String?, at: Instant)
}
