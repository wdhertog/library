package nl.wdhertog.library.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Eén wijziging zoals die tussen telefoons gaat. */
data class SyncChange(
    val seq: Long,
    val entity: String,
    val uuid: String,
    /** Gewijzigde gedeelde velden met hun nieuwe waarde; leeg bij een verwijdering. */
    val fields: JsonObject,
    val hlc: String,
    val deleted: Boolean,
)

/** Wat de engine van de database nodig heeft; RoomSyncStore in de app, een in-memory versie in tests. */
interface SyncStore {
    suspend fun <T> transaction(block: suspend () -> T): T

    suspend fun dirty(): List<SyncDirty>
    suspend fun clearDirty(entity: String, uuid: String)

    /** De gedeelde velden van een record, of null als het er niet is. */
    suspend fun read(entity: String, uuid: String): JsonObject?

    /** Velden schrijven; maakt het record aan als het er nog niet is. */
    suspend fun write(entity: String, uuid: String, fields: JsonObject)
    suspend fun delete(entity: String, uuid: String)

    /** Heeft dit record leesgeschiedenis van deze telefoon? Dan niet verwijderen maar loskoppelen. */
    suspend fun hasPersonalHistory(entity: String, uuid: String): Boolean

    /** Lokaal bewaren als "niet in bezit", los van de gedeelde collectie. */
    suspend fun detach(entity: String, uuid: String)

    suspend fun shadow(entity: String, uuid: String): String?
    suspend fun setShadow(entity: String, uuid: String, data: String)
    suspend fun clocks(entity: String, uuid: String): Map<String, String>
    suspend fun setClock(entity: String, uuid: String, field: String, hlc: String)
    suspend fun removeClocks(entity: String, uuid: String)

    suspend fun appendOutbox(entity: String, uuid: String, fields: String, hlc: String, deleted: Boolean): Long
    suspend fun outboxAfter(seq: Long): List<SyncChange>

    /** Na het toepassen: afgeleide gegevens bijwerken (zoektekst, verweesde werken en auteurs). */
    suspend fun afterApply(touched: Set<Pair<String, String>>)

    // --- Eerste keer samenvoegen (FirstMerge) ---

    /** Alle gedeelde records. */
    suspend fun snapshot(): List<SnapshotRecord>

    /** Een record de uuid geven die hetzelfde record op de andere telefoon heeft. */
    suspend fun rename(entity: String, from: String, to: String)

    /** Alles over eerder uitwisselen vergeten en alle records als gewijzigd markeren. */
    suspend fun restartTracking()
}

/**
 * Het hart van de synchronisatie (ontwerp §11):
 * - [prepareOutgoing] zet wat op deze telefoon veranderde in de outbox, alleen de velden die echt anders zijn;
 * - [applyIncoming] voegt wijzigingen van de ander samen, per veld: de nieuwste wint (hybride klok).
 *
 * Wat wint, gaat ook in de eigen outbox: zo geeft een telefoon wijzigingen door aan zijn andere
 * gekoppelde telefoons (A–B en B–C koppelen is genoeg). Komt het bij de bron terug, dan heeft het
 * daar dezelfde tijdstempel, wint het niet en gaat het niet verder.
 *
 * Verwijderen wint altijd van een wijziging aan hetzelfde record (voorspelbaar, en beide telefoons
 * komen op hetzelfde uit), met één uitzondering: een boek waar deze telefoon leesgeschiedenis bij heeft,
 * blijft hier als "niet in bezit" en doet daarna niet meer mee.
 *
 * Volgorde per sessie: eerst [prepareOutgoing], dan uitwisselen, dan [applyIncoming]. Zo hebben lokale
 * wijzigingen al een tijdstempel voordat ze met binnenkomende vergeleken worden.
 */
class SyncEngine(
    private val store: SyncStore,
    private val clock: HybridClock,
    /** De klokstand bewaren, zodat die na een herstart niet terugloopt. */
    private val saveClock: suspend (String) -> Unit,
) {
    suspend fun prepareOutgoing(): Int = store.transaction { prepareDirty() }

    /** Lokale wijzigingen een tijdstempel geven en in de outbox zetten; binnen een transactie aanroepen. */
    private suspend fun prepareDirty(): Int {
        var added = 0
        for (dirty in store.dirty()) {
            val shadow = store.shadow(dirty.entity, dirty.uuid)
            when {
                // Al verwijderd of losgekoppeld: niets meer te melden.
                shadow == TOMBSTONE || shadow == DETACHED -> Unit
                dirty.deleted -> if (shadow != null) {
                    // Alleen melden als de ander het record kent; iets wat nooit gedeeld is, verdwijnt stil.
                    store.appendOutbox(dirty.entity, dirty.uuid, "{}", tick(), deleted = true)
                    store.setShadow(dirty.entity, dirty.uuid, TOMBSTONE)
                    store.removeClocks(dirty.entity, dirty.uuid)
                    added++
                }
                else -> store.read(dirty.entity, dirty.uuid)?.let { current ->
                    val changed = changedFields(shadow?.let(::parse) ?: JsonObject(emptyMap()), current)
                    if (changed.isNotEmpty()) {
                        val hlc = tick()
                        changed.keys.forEach { store.setClock(dirty.entity, dirty.uuid, it, hlc) }
                        store.appendOutbox(dirty.entity, dirty.uuid, JsonObject(changed).toString(), hlc, deleted = false)
                        store.setShadow(dirty.entity, dirty.uuid, current.toString())
                        added++
                    }
                }
            }
            store.clearDirty(dirty.entity, dirty.uuid)
        }
        return added
    }

    suspend fun changesAfter(seq: Long): List<SyncChange> = store.outboxAfter(seq)

    suspend fun snapshot(): List<SnapshotRecord> = store.snapshot()

    /** Huidige klokstand, voor de andere telefoon bij het eerste samenvoegen. */
    fun clockNow(): String = clock.current.toString()

    suspend fun applyIncoming(changes: List<SyncChange>): Int = store.transaction {
        // Wat de gebruiker tijdens de uitwisseling wijzigde, eerst een tijdstempel geven: anders wint de
        // binnenkomende waarde, wordt de schaduw gelijk aan de huidige waarde en verdwijnt de wijziging stil.
        prepareDirty()
        val touched = mutableSetOf<Pair<String, String>>()
        val ordered = changes.sortedWith(compareBy({ SyncSchema.applyOrder.indexOf(it.entity) }, { it.seq }))
        for (change in ordered) {
            clock.receive(HybridClock.Timestamp.parse(change.hlc))
            val key = change.entity to change.uuid
            val shadow = store.shadow(change.entity, change.uuid)
            if (shadow == TOMBSTONE || shadow == DETACHED) continue

            if (change.deleted) {
                if (store.read(change.entity, change.uuid) != null) {
                    if (store.hasPersonalHistory(change.entity, change.uuid)) {
                        store.detach(change.entity, change.uuid)
                        store.setShadow(change.entity, change.uuid, DETACHED)
                    } else {
                        store.delete(change.entity, change.uuid)
                        store.setShadow(change.entity, change.uuid, TOMBSTONE)
                    }
                } else {
                    store.setShadow(change.entity, change.uuid, TOMBSTONE)
                }
                store.removeClocks(change.entity, change.uuid)
                store.appendOutbox(change.entity, change.uuid, "{}", change.hlc, deleted = true)
                touched += key
                continue
            }

            val local = store.clocks(change.entity, change.uuid)
            val winners = change.fields.filterKeys { field -> local[field]?.let { change.hlc > it } ?: true }
            if (winners.isEmpty()) continue
            store.write(change.entity, change.uuid, JsonObject(winners))
            winners.keys.forEach { store.setClock(change.entity, change.uuid, it, change.hlc) }
            // De schaduw krijgt alleen de binnengekomen velden; eigen, nog niet verstuurde wijzigingen blijven zo zichtbaar.
            val previous = shadow?.let(::parse) ?: JsonObject(emptyMap())
            store.setShadow(change.entity, change.uuid, JsonObject(previous + winners).toString())
            // Doorgeven aan de telefoons waarmee deze gekoppeld is, met de oorspronkelijke tijdstempel.
            store.appendOutbox(change.entity, change.uuid, JsonObject(winners).toString(), change.hlc, deleted = false)
            touched += key
        }
        saveClock(clock.current.toString())
        store.afterApply(touched)
        touched.size
    }

    private suspend fun tick(): String = clock.now().toString().also { saveClock(it) }

    companion object {
        /** Schaduw van een verwijderd record: latere wijzigingen eraan worden genegeerd. */
        const val TOMBSTONE = "{\"_deleted\":true}"

        /** Schaduw van een lokaal bewaard (losgekoppeld) boek: doet niet meer mee. */
        const val DETACHED = "{\"_detached\":true}"

        private fun parse(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

        /** Velden waarvan de waarde anders is dan in de schaduw; een ontbrekend veld telt als null. */
        internal fun changedFields(old: JsonObject, current: JsonObject): Map<String, JsonElement> =
            (old.keys + current.keys)
                .filter { (old[it] ?: JsonNull) != (current[it] ?: JsonNull) }
                .associateWith { current[it] ?: JsonNull }
    }
}
