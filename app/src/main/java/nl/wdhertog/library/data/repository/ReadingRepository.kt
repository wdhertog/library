package nl.wdhertog.library.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import nl.wdhertog.library.data.db.DatePrecision
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.data.db.ReadingGoal
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.db.ReadingStatus
import nl.wdhertog.library.data.db.SessionRow
import java.time.Instant
import java.time.LocalDate

class ReadingRepository(private val db: LibraryDatabase) {
    private val dao = db.readingDao()

    fun observeSessions(): Flow<List<ReadingSession>> = dao.observeSessions()

    fun observeSessionsForWork(bookId: Long): Flow<List<SessionRow>> = dao.observeSessionsForWork(bookId)

    fun observeGoals(): Flow<List<ReadingGoal>> = dao.observeGoals()

    fun observeReadBookIds(): Flow<List<Long>> = dao.observeBookIdsWithStatus(ReadingStatus.FINISHED)

    fun observeReadingBookIds(): Flow<List<Long>> = dao.observeBookIdsWithStatus(ReadingStatus.READING)

    /** Beginnen met lezen; haalt het boek van de leeslijst. */
    suspend fun start(bookId: Long, date: LocalDate = LocalDate.now()) = db.withTransaction {
        dao.insert(ReadingSession(bookId = bookId, startedAt = date, status = ReadingStatus.READING))
        dao.setWantToRead(bookId, false, Instant.now())
    }

    suspend fun updateProgress(sessionId: Long, page: Int?, percent: Int?) {
        val session = dao.get(sessionId) ?: return
        dao.update(session.copy(progressPage = page, progressPercent = percent, updatedAt = Instant.now()))
    }

    /** Nieuwe sessie (bijv. een boek dat je vroeger las) of een bestaande bijwerken (id != 0). */
    suspend fun save(session: ReadingSession) = db.withTransaction {
        val cleaned = session.copy(
            // Klaar of gestopt: voortgang is niet meer van belang.
            progressPage = session.progressPage.takeIf { session.status == ReadingStatus.READING },
            progressPercent = session.progressPercent.takeIf { session.status == ReadingStatus.READING },
            datePrecision = if (session.finishedAt == null && session.startedAt == null) DatePrecision.DAY else session.datePrecision,
            review = session.review?.trim()?.ifEmpty { null },
            updatedAt = Instant.now(),
        )
        if (cleaned.id == 0L) {
            dao.insert(cleaned)
            // Gelezen of begonnen: dan staat het niet meer op de leeslijst.
            dao.setWantToRead(cleaned.bookId, false, Instant.now())
        } else {
            dao.update(cleaned)
        }
    }

    suspend fun delete(sessionId: Long) = dao.delete(sessionId)

    suspend fun setWantToRead(bookId: Long, wantToRead: Boolean) = dao.setWantToRead(bookId, wantToRead, Instant.now())

    suspend fun setGoal(year: Int, target: Int?) {
        if (target == null || target <= 0) dao.deleteGoal(year) else dao.upsertGoal(ReadingGoal(year, target))
    }
}
