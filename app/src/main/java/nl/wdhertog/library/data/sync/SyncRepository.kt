package nl.wdhertog.library.data.sync

import androidx.room.withTransaction
import nl.wdhertog.library.data.db.LibraryDatabase
import java.util.UUID

/**
 * Basis voor synchronisatie (ontwerp §11, stap S1): eigen telefoon-id, de hybride klok en
 * of het vastleggen van wijzigingen aan staat. Uitwisselen zelf komt in de volgende stappen.
 */
class SyncRepository(private val db: LibraryDatabase) {
    private val dao = db.syncDao()
    private var clock: HybridClock? = null

    /** Vaste id van deze telefoon; wordt de eerste keer gemaakt. */
    suspend fun deviceId(): String = db.withTransaction {
        dao.meta(SyncKeys.DEVICE_ID) ?: UUID.randomUUID().toString().also { dao.setMeta(SyncMeta(SyncKeys.DEVICE_ID, it)) }
    }

    suspend fun clock(): HybridClock =
        clock ?: HybridClock(deviceId()).also {
            it.restore(dao.meta(SyncKeys.CLOCK))
            clock = it
        }

    /** Nieuw tijdstempel, meteen bewaard zodat de klok na een herstart niet terugloopt. */
    suspend fun tick(): HybridClock.Timestamp {
        val now = clock().now()
        dao.setMeta(SyncMeta(SyncKeys.CLOCK, now.toString()))
        return now
    }

    suspend fun isEnabled(): Boolean = dao.meta(SyncKeys.ENABLED) == "1"

    /** Vanaf nu legen de triggers wijzigingen vast (bij het koppelen van de eerste telefoon). */
    suspend fun setEnabled(enabled: Boolean) = dao.setMeta(SyncMeta(SyncKeys.ENABLED, if (enabled) "1" else "0"))
}
