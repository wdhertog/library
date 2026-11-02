package nl.wdhertog.library.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.Instant

data class LocationBookCount(val locationId: Long, val count: Int)

@Dao
interface LocationDao {

    @Query("SELECT * FROM location ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeLocations(): Flow<List<Location>>

    @Query("SELECT * FROM location ORDER BY sortOrder, name COLLATE NOCASE")
    suspend fun getLocations(): List<Location>

    @Query("SELECT * FROM location WHERE id = :id")
    suspend fun getLocation(id: Long): Location?

    /** Aantal boeken direct op elke locatie (zonder sublocaties). */
    @Query("SELECT locationId, COUNT(*) AS count FROM book WHERE locationId IS NOT NULL GROUP BY locationId")
    fun observeBookCounts(): Flow<List<LocationBookCount>>

    @Query("SELECT COALESCE(MAX(sortOrder) + 1, 0) FROM location WHERE parentId IS :parentId")
    suspend fun nextSortOrder(parentId: Long?): Int

    @Insert
    suspend fun insert(location: Location): Long

    @Update
    suspend fun update(location: Location)

    /** Sublocaties gaan mee (CASCADE); boeken verliezen hun locatie (SET NULL). */
    @Query("DELETE FROM location WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COALESCE(MAX(shelfPosition) + 1, 0) FROM book WHERE locationId = :locationId")
    suspend fun nextShelfPosition(locationId: Long): Int

    @Query("UPDATE book SET locationId = :locationId, shelfPosition = :position, updatedAt = :now WHERE id = :bookId")
    suspend fun setBookLocation(bookId: Long, locationId: Long?, position: Int?, now: Instant)
}
