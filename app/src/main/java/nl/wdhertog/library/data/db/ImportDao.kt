package nl.wdhertog.library.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Een boek met titel, ISBN en auteurs, om geïmporteerde boeken te herkennen. */
data class BookIdentityRow(
    val id: Long,
    val title: String,
    val isbn13: String?,
    val hirtId: Long?,
    /** Auteurs van het eerste werk, gescheiden door een tab. */
    val authors: String?,
)

@Dao
interface ImportDao {

    @Query(
        """
        SELECT b.id, b.title, b.isbn13, b.hirtId,
               (SELECT GROUP_CONCAT(a.name, char(9)) FROM book_work bw
                  JOIN work_author wa ON wa.workId = bw.workId JOIN author a ON a.id = wa.authorId
                 WHERE bw.bookId = b.id AND bw.position = 0) AS authors
        FROM book b
        """
    )
    suspend fun bookIdentities(): List<BookIdentityRow>

    @Insert
    suspend fun insertRun(run: ImportRun): Long

    @Update
    suspend fun updateRun(run: ImportRun)

    @Query("SELECT * FROM import_run ORDER BY importedAt DESC")
    fun observeRuns(): Flow<List<ImportRun>>

    @Query("DELETE FROM import_run WHERE id = :id")
    suspend fun deleteRun(id: Long)

    @Query("SELECT id FROM book WHERE importRunId = :runId")
    suspend fun booksOfRun(runId: Long): List<Long>

    // --- Lezen ---

    @Query("SELECT * FROM reading_session WHERE bookId = :bookId ORDER BY COALESCE(finishedAt, startedAt)")
    suspend fun sessionsForBook(bookId: Long): List<ReadingSession>

    @Insert
    suspend fun insertSession(session: ReadingSession): Long

    @Update
    suspend fun updateSession(session: ReadingSession)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGoal(goal: ReadingGoal)

    @Query("SELECT * FROM reading_goal WHERE year = :year")
    suspend fun goal(year: Int): ReadingGoal?
}
