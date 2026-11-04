package nl.wdhertog.library.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Een leessessie met het exemplaar waarin je las (kan een andere editie van hetzelfde werk zijn). */
data class SessionRow(
    @Embedded val session: ReadingSession,
    val bookTitle: String,
    val bookLanguage: String?,
)

@Dao
interface ReadingDao {

    @Query("SELECT * FROM reading_session")
    fun observeSessions(): Flow<List<ReadingSession>>

    /**
     * Sessies van dit boek én van andere edities van hetzelfde werk: "gelezen" geldt voor het werk.
     * Nieuwste (of lopende) eerst.
     */
    @Query(
        """
        SELECT s.*, b.title AS bookTitle, b.language AS bookLanguage
        FROM reading_session s JOIN book b ON b.id = s.bookId
        WHERE s.bookId = :bookId
           OR s.bookId IN (SELECT other.bookId FROM book_work mine
                           JOIN book_work other ON other.workId = mine.workId
                           WHERE mine.bookId = :bookId)
        ORDER BY s.status != 'READING', COALESCE(s.finishedAt, s.startedAt) IS NULL, COALESCE(s.finishedAt, s.startedAt) DESC
        """
    )
    fun observeSessionsForWork(bookId: Long): Flow<List<SessionRow>>

    /** Boeken met een sessie met [status], ook als die sessie bij een andere editie van hetzelfde werk hoort. */
    @Query(
        """
        SELECT DISTINCT other.bookId FROM reading_session s
        JOIN book_work mine ON mine.bookId = s.bookId
        JOIN book_work other ON other.workId = mine.workId
        WHERE s.status = :status
        """
    )
    fun observeBookIdsWithStatus(status: ReadingStatus): Flow<List<Long>>

    /** Laatste keer uitgelezen per boek, ook via een andere editie van hetzelfde werk. */
    @Query(
        """
        SELECT other.bookId AS bookId, MAX(s.finishedAt) AS finishedAt FROM reading_session s
        JOIN book_work mine ON mine.bookId = s.bookId
        JOIN book_work other ON other.workId = mine.workId
        WHERE s.status = 'FINISHED'
        GROUP BY other.bookId
        """
    )
    fun observeLastFinished(): Flow<List<BookFinished>>

    /** Voortgang van de boeken die je nu leest. */
    @Query(
        """
        SELECT s.bookId AS bookId, s.progressPage AS progressPage, s.progressPercent AS progressPercent, b.pages AS pages
        FROM reading_session s JOIN book b ON b.id = s.bookId
        WHERE s.status = 'READING'
        """
    )
    fun observeProgress(): Flow<List<BookProgress>>

    @Insert
    suspend fun insert(session: ReadingSession): Long

    @Update
    suspend fun update(session: ReadingSession)

    @Query("DELETE FROM reading_session WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM reading_session WHERE id = :id")
    suspend fun get(id: Long): ReadingSession?

    @Query("UPDATE book SET wantToRead = :wantToRead, updatedAt = :now WHERE id = :bookId")
    suspend fun setWantToRead(bookId: Long, wantToRead: Boolean, now: java.time.Instant)

    @Query("SELECT * FROM reading_goal")
    fun observeGoals(): Flow<List<ReadingGoal>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGoal(goal: ReadingGoal)

    @Query("DELETE FROM reading_goal WHERE year = :year")
    suspend fun deleteGoal(year: Int)
}

data class BookFinished(val bookId: Long, val finishedAt: java.time.LocalDate?)

data class BookProgress(val bookId: Long, val progressPage: Int?, val progressPercent: Int?, val pages: Int?) {
    /** Percentage, uit het opgegeven percentage of uit pagina's. */
    val percent: Int?
        get() = progressPercent ?: progressPage?.let { page -> pages?.takeIf { it > 0 }?.let { (page * 100 / it).coerceIn(0, 100) } }
}
