package nl.wdhertog.library.data.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import nl.wdhertog.library.data.db.Book
import nl.wdhertog.library.data.db.Loan
import nl.wdhertog.library.data.db.Location
import nl.wdhertog.library.data.db.Person
import nl.wdhertog.library.data.db.Series
import nl.wdhertog.library.data.db.Work

@Dao
interface SyncDao {

    @Query("SELECT value FROM sync_meta WHERE key = :key")
    suspend fun meta(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setMeta(meta: SyncMeta)

    @Query("SELECT * FROM sync_dirty")
    suspend fun dirty(): List<SyncDirty>

    @Query("DELETE FROM sync_dirty WHERE entity = :entity AND uuid = :uuid")
    suspend fun clearDirty(entity: String, uuid: String)

    @Query("SELECT * FROM sync_peer ORDER BY pairedAt")
    suspend fun peers(): List<SyncPeer>

    // --- Schaduw, klokken en outbox ---

    @Query("SELECT data FROM sync_shadow WHERE entity = :entity AND uuid = :uuid")
    suspend fun shadow(entity: String, uuid: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setShadow(shadow: SyncShadow)

    @Query("SELECT * FROM sync_field_clock WHERE entity = :entity AND uuid = :uuid")
    suspend fun clocks(entity: String, uuid: String): List<SyncFieldClock>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setClock(clock: SyncFieldClock)

    @Query("DELETE FROM sync_field_clock WHERE entity = :entity AND uuid = :uuid")
    suspend fun removeClocks(entity: String, uuid: String)

    @Insert
    suspend fun appendOutbox(entry: SyncOutbox): Long

    @Query("SELECT * FROM sync_outbox WHERE seq > :seq ORDER BY seq")
    suspend fun outboxAfter(seq: Long): List<SyncOutbox>

    // --- Records op uuid ---

    @Query("SELECT * FROM book WHERE uuid = :uuid") suspend fun book(uuid: String): Book?
    @Query("SELECT * FROM work WHERE uuid = :uuid") suspend fun work(uuid: String): Work?
    @Query("SELECT * FROM series WHERE uuid = :uuid") suspend fun series(uuid: String): Series?
    @Query("SELECT * FROM location WHERE uuid = :uuid") suspend fun location(uuid: String): Location?
    @Query("SELECT * FROM person WHERE uuid = :uuid") suspend fun person(uuid: String): Person?
    @Query("SELECT * FROM loan WHERE uuid = :uuid") suspend fun loan(uuid: String): Loan?

    @Query("SELECT uuid FROM book WHERE id = :id") suspend fun bookUuid(id: Long): String?
    @Query("SELECT uuid FROM series WHERE id = :id") suspend fun seriesUuid(id: Long): String?
    @Query("SELECT uuid FROM location WHERE id = :id") suspend fun locationUuid(id: Long): String?
    @Query("SELECT uuid FROM person WHERE id = :id") suspend fun personUuid(id: Long): String?

    @Query("SELECT w.uuid FROM book_work bw JOIN work w ON w.id = bw.workId WHERE bw.bookId = :bookId ORDER BY bw.position")
    suspend fun workUuidsOfBook(bookId: Long): List<String>

    @Query("SELECT id, uuid FROM work WHERE uuid IN (:uuids)")
    suspend fun workIdsByUuid(uuids: List<String>): List<IdUuid>

    @Query("DELETE FROM book_work WHERE bookId = :bookId")
    suspend fun clearBookWorks(bookId: Long)

    @Query("SELECT COUNT(*) FROM reading_session WHERE bookId = :bookId")
    suspend fun readingSessionCount(bookId: Long): Int

    @Insert suspend fun insertSeries(series: Series): Long
    @Update suspend fun updateSeries(series: Series)
    @Insert suspend fun insertPerson(person: Person): Long
    @Update suspend fun updatePerson(person: Person)
    @Insert suspend fun insertLoan(loan: Loan): Long
    @Update suspend fun updateLoan(loan: Loan)

    @Query("DELETE FROM work WHERE id = :id") suspend fun deleteWork(id: Long)
    @Query("DELETE FROM series WHERE id = :id") suspend fun deleteSeries(id: Long)
    @Query("DELETE FROM person WHERE id = :id") suspend fun deletePerson(id: Long)
}

data class IdUuid(val id: Long, val uuid: String)
