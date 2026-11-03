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

    suspend fun saveClock(value: String) = dao.setMeta(SyncMeta(SyncKeys.CLOCK, value))

    suspend fun peers() = dao.peers()

    suspend fun isEnabled(): Boolean = dao.meta(SyncKeys.ENABLED) == "1"

    /** Vanaf nu legen de triggers wijzigingen vast (bij het koppelen van de eerste telefoon). */
    suspend fun setEnabled(enabled: Boolean) = dao.setMeta(SyncMeta(SyncKeys.ENABLED, if (enabled) "1" else "0"))

    /**
     * Synchroniseren aanzetten. De eerste keer gaat alles wat er al is mee als wijziging, zodat
     * de andere telefoon de hele collectie krijgt.
     */
    suspend fun enable() = db.withTransaction {
        if (isEnabled()) return@withTransaction
        markAll(dao)
        setEnabled(true)
    }

    fun observePeers() = dao.observePeers()

    companion object {
        /** Alle bestaande gedeelde records als gewijzigd markeren. */
        suspend fun markAll(dao: SyncDao) {
            dao.markAllBook(); dao.markAllWork(); dao.markAllSeries()
            dao.markAllLocation(); dao.markAllPerson(); dao.markAllLoan()
        }
    }

    suspend fun unpair(deviceId: String) = dao.deletePeer(deviceId)

    /** Gekoppelde telefoons voor [nl.wdhertog.library.sync.SyncSession]. */
    val peerStore = object : nl.wdhertog.library.sync.PeerStore {
        override suspend fun all() = dao.peers().map {
            nl.wdhertog.library.sync.KnownPeer(it.deviceId, it.name, it.certificateFingerprint, it.receivedUpTo)
        }
        override suspend fun find(deviceId: String) = dao.peer(deviceId)?.let {
            nl.wdhertog.library.sync.KnownPeer(it.deviceId, it.name, it.certificateFingerprint, it.receivedUpTo)
        }
        override suspend fun save(peer: nl.wdhertog.library.sync.KnownPeer) {
            val existing = dao.peer(peer.deviceId)
            dao.savePeer(
                SyncPeer(
                    deviceId = peer.deviceId,
                    name = peer.name,
                    certificateFingerprint = peer.fingerprint,
                    receivedUpTo = peer.receivedUpTo,
                    lastSyncAt = existing?.lastSyncAt,
                    pairedAt = existing?.pairedAt ?: System.currentTimeMillis(),
                ),
            )
        }
        override suspend fun markReceived(deviceId: String, upTo: Long, at: Long) = dao.markReceived(deviceId, upTo, at)
    }
}
