package nl.wdhertog.library.data.sync

import androidx.room.Entity
import androidx.room.PrimaryKey

/*
 * Tabellen voor synchronisatie tussen telefoons (ontwerp §11). Ze horen bij één telefoon en worden
 * zelf nooit uitgewisseld; ze houden bij wat er veranderd is en wat de ander al heeft.
 */

/** Losse instellingen: telefoon-id, laatste klokstand, of synchroniseren aan staat. */
@Entity(tableName = "sync_meta")
data class SyncMeta(
    @PrimaryKey val key: String,
    val value: String,
)

/**
 * Records die sinds de laatste uitwisseling veranderd zijn. Gevuld door triggers op de gedeelde
 * tabellen (SyncTriggers), dus ook bij bulkacties en achtergrondverrijking.
 */
@Entity(tableName = "sync_dirty", primaryKeys = ["entity", "uuid"])
data class SyncDirty(
    val entity: String,
    val uuid: String,
    val deleted: Boolean,
)

/** De laatst uitgewisselde stand van een record (alleen gedeelde velden, als JSON), om te zien wat er echt anders is. */
@Entity(tableName = "sync_shadow", primaryKeys = ["entity", "uuid"])
data class SyncShadow(
    val entity: String,
    val uuid: String,
    val data: String,
)

/** Per veld: wanneer het voor het laatst gewijzigd is (hybride klok). Een binnenkomende wijziging wint alleen als die nieuwer is. */
@Entity(tableName = "sync_field_clock", primaryKeys = ["entity", "uuid", "field"])
data class SyncFieldClock(
    val entity: String,
    val uuid: String,
    val field: String,
    val hlc: String,
)

/** Wijzigingen van deze telefoon, klaar om te versturen; een gekoppelde telefoon vraagt "alles na nummer N". */
@Entity(tableName = "sync_outbox")
data class SyncOutbox(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val entity: String,
    val uuid: String,
    /** Gewijzigde velden met hun waarde, als JSON-object; leeg bij een verwijdering. */
    val fields: String,
    val hlc: String,
    val deleted: Boolean,
)

/** Een gekoppelde telefoon. */
@Entity(tableName = "sync_peer")
data class SyncPeer(
    @PrimaryKey val deviceId: String,
    val name: String,
    /** SHA-256 van het certificaat van de ander, vastgelegd bij het koppelen. */
    val certificateFingerprint: String,
    /** Tot en met welk volgnummer uit de outbox van de ander alles verwerkt is. */
    val receivedUpTo: Long = 0,
    val lastSyncAt: Long? = null,
    val pairedAt: Long,
)
