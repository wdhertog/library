package nl.wdhertog.library.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Een auteur in de lijst Auteurs. */
data class AuthorSummary(val id: Long, val name: String, val books: Int, val works: Int)

data class AuthorCountry(val name: String, val country: String)

/** Welk boek bij welk werk hoort, voor de boeken van een auteur. */
data class BookWorkLink(val bookId: Long, val workId: Long, val primaryBookUuid: String? = null)

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

    @Query("SELECT * FROM author")
    suspend fun all(): List<Author>

    /** Auteurs met boeken van wie Wikidata nog niet is nagevraagd (voor land en levensjaren). */
    @Query("SELECT id FROM author WHERE infoFetchedAt IS NULL AND id IN (SELECT authorId FROM work_author) ORDER BY id LIMIT :limit")
    suspend fun withoutInfo(limit: Int): List<Long>

    /** Open Library-werken van deze auteur, om hem daar te vinden. */
    @Query("SELECT DISTINCT w.openLibraryWorkId FROM work w JOIN work_author wa ON wa.workId = w.id WHERE wa.authorId = :authorId AND w.openLibraryWorkId IS NOT NULL LIMIT 5")
    suspend fun openLibraryWorkIds(authorId: Long): List<String>

    /** "Niet deze persoon": koppeling en gegevens weg, en niet opnieuw automatisch koppelen. */
    @Query("UPDATE author SET wikidataId = NULL, birthYear = NULL, deathYear = NULL, country = NULL, description = NULL, wikidataRejected = 1, infoFetchedAt = :now WHERE id = :id")
    suspend fun reject(id: Long, now: Instant)

    @Query("UPDATE author SET wikidataRejected = 0 WHERE id = :id")
    suspend fun unreject(id: Long)

    /** Auteurs zonder koppeling opnieuw laten nalopen (na een betere zoekmethode). */
    @Query("UPDATE author SET infoFetchedAt = NULL WHERE wikidataId IS NULL AND wikidataRejected = 0")
    suspend fun retryUnlinked(): Int

    /** Naam → land, voor de badges per land. */
    @Query("SELECT name, country FROM author WHERE country IS NOT NULL")
    fun observeCountries(): Flow<List<AuthorCountry>>

    @Query("SELECT id FROM author WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findId(name: String): Long?

    /** Een andere auteur met deze naam (hoofdletters tellen niet). */
    @Query("SELECT * FROM author WHERE name = :name COLLATE NOCASE AND id != :excludeId LIMIT 1")
    suspend fun findOther(name: String, excludeId: Long): Author?

    @Query("SELECT DISTINCT bw.bookId FROM book_work bw JOIN work_author wa ON wa.workId = bw.workId WHERE wa.authorId = :authorId")
    suspend fun bookIds(authorId: Long): List<Long>

    /** Alle auteurs van de werken van deze auteur (ook co-auteurs). */
    @Query("SELECT * FROM work_author WHERE workId IN (SELECT workId FROM work_author WHERE authorId = :authorId)")
    suspend fun linksOfWorksBy(authorId: Long): List<WorkAuthor>

    @Query("DELETE FROM work_author WHERE workId IN (:workIds)")
    suspend fun deleteLinksOfWorks(workIds: List<Long>)

    @androidx.room.Insert
    suspend fun insertLinks(links: List<WorkAuthor>)

    @Query("DELETE FROM author WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE author SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @androidx.room.Update
    suspend fun update(author: Author)

    @Query(
        """
        SELECT DISTINCT bw.bookId, bw.workId, w.primaryBookUuid FROM book_work bw
        JOIN work_author wa ON wa.workId = bw.workId
        JOIN work w ON w.id = bw.workId
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
