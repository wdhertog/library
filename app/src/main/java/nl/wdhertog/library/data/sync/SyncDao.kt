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

    @Query("SELECT COUNT(*) FROM sync_dirty")
    fun observeDirtyCount(): kotlinx.coroutines.flow.Flow<Int>

    @Query("SELECT DISTINCT coverFile FROM book WHERE coverFile IS NOT NULL")
    suspend fun coverFiles(): List<String>

    @Query("DELETE FROM sync_dirty WHERE entity = :entity AND uuid = :uuid")
    suspend fun clearDirty(entity: String, uuid: String)

    @Query("SELECT * FROM sync_peer ORDER BY pairedAt")
    suspend fun peers(): List<SyncPeer>

    @Query("SELECT * FROM sync_peer ORDER BY pairedAt")
    fun observePeers(): kotlinx.coroutines.flow.Flow<List<SyncPeer>>

    @Query("SELECT * FROM sync_peer WHERE deviceId = :deviceId")
    suspend fun peer(deviceId: String): SyncPeer?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePeer(peer: SyncPeer)

    @Query("UPDATE sync_peer SET receivedUpTo = :upTo, lastSyncAt = :at WHERE deviceId = :deviceId")
    suspend fun markReceived(deviceId: String, upTo: Long, at: Long)

    @Query("DELETE FROM sync_peer WHERE deviceId = :deviceId")
    suspend fun deletePeer(deviceId: String)

    // Alle bestaande records als gewijzigd markeren, bij het aanzetten van synchroniseren.
    @Query("INSERT INTO sync_dirty (entity, uuid, deleted) SELECT 'book', uuid, 0 FROM book WHERE uuid NOT IN (SELECT uuid FROM sync_dirty WHERE entity = 'book')")
    suspend fun markAllBook()
    @Query("INSERT INTO sync_dirty (entity, uuid, deleted) SELECT 'work', uuid, 0 FROM work WHERE uuid NOT IN (SELECT uuid FROM sync_dirty WHERE entity = 'work')")
    suspend fun markAllWork()
    @Query("INSERT INTO sync_dirty (entity, uuid, deleted) SELECT 'series', uuid, 0 FROM series WHERE uuid NOT IN (SELECT uuid FROM sync_dirty WHERE entity = 'series')")
    suspend fun markAllSeries()
    @Query("INSERT INTO sync_dirty (entity, uuid, deleted) SELECT 'location', uuid, 0 FROM location WHERE uuid NOT IN (SELECT uuid FROM sync_dirty WHERE entity = 'location')")
    suspend fun markAllLocation()
    @Query("INSERT INTO sync_dirty (entity, uuid, deleted) SELECT 'person', uuid, 0 FROM person WHERE uuid NOT IN (SELECT uuid FROM sync_dirty WHERE entity = 'person')")
    suspend fun markAllPerson()
    @Query("INSERT INTO sync_dirty (entity, uuid, deleted) SELECT 'loan', uuid, 0 FROM loan WHERE uuid NOT IN (SELECT uuid FROM sync_dirty WHERE entity = 'loan')")
    suspend fun markAllLoan()

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

    // --- Eerste keer samenvoegen ---

    @Query("SELECT uuid, updatedAt FROM book") suspend fun timesBook(): List<UuidTime>
    @Query("SELECT uuid, updatedAt FROM work") suspend fun timesWork(): List<UuidTime>
    @Query("SELECT uuid, updatedAt FROM series") suspend fun timesSeries(): List<UuidTime>
    @Query("SELECT uuid, updatedAt FROM location") suspend fun timesLocation(): List<UuidTime>
    @Query("SELECT uuid, updatedAt FROM person") suspend fun timesPerson(): List<UuidTime>
    @Query("SELECT uuid, updatedAt FROM loan") suspend fun timesLoan(): List<UuidTime>
    @Query("UPDATE book SET uuid = :to WHERE uuid = :from") suspend fun renameBook(from: String, to: String)
    @Query("UPDATE work SET uuid = :to WHERE uuid = :from") suspend fun renameWork(from: String, to: String)
    @Query("UPDATE series SET uuid = :to WHERE uuid = :from") suspend fun renameSeries(from: String, to: String)
    @Query("UPDATE location SET uuid = :to WHERE uuid = :from") suspend fun renameLocation(from: String, to: String)
    @Query("UPDATE person SET uuid = :to WHERE uuid = :from") suspend fun renamePerson(from: String, to: String)
    @Query("UPDATE loan SET uuid = :to WHERE uuid = :from") suspend fun renameLoan(from: String, to: String)
    @Query("DELETE FROM sync_outbox") suspend fun clearOutbox()
    @Query("DELETE FROM sync_shadow") suspend fun clearShadows()
    @Query("DELETE FROM sync_field_clock") suspend fun clearClocks()
    @Query("DELETE FROM sync_dirty") suspend fun clearAllDirty()
}

data class IdUuid(val id: Long, val uuid: String)

data class UuidTime(val uuid: String, val updatedAt: Long)
