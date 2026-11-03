package nl.wdhertog.library.data.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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
}
