package nl.wdhertog.library.data.sync

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Een telefoon in het geheugen voor tests van SyncEngine. Elke write en delete markeert het record
 * als gewijzigd, net als de triggers in de echte database (ook bij wijzigingen van de ander).
 */
class InMemorySyncStore : SyncStore {
    val records = mutableMapOf<Pair<String, String>, JsonObject>()
    val personalHistory = mutableSetOf<Pair<String, String>>()
    val detached = mutableSetOf<Pair<String, String>>()
    private val dirty = linkedMapOf<Pair<String, String>, Boolean>()
    private val shadows = mutableMapOf<Pair<String, String>, String>()
    private val clocks = mutableMapOf<Pair<String, String>, MutableMap<String, String>>()
    private val outbox = mutableListOf<SyncChange>()

    /** Een wijziging door de gebruiker van deze telefoon. */
    fun edit(entity: String, uuid: String, vararg fields: Pair<String, Any?>) {
        val key = entity to uuid
        records[key] = JsonObject((records[key] ?: JsonObject(emptyMap())) + fields.associate { (k, v) -> k to json(v) })
        dirty[key] = false
    }

    fun remove(entity: String, uuid: String) {
        records.remove(entity to uuid)
        dirty[entity to uuid] = true
    }

    fun field(entity: String, uuid: String, field: String): Any? = records[entity to uuid]?.get(field)?.let {
        (it as? kotlinx.serialization.json.JsonPrimitive)?.content ?: it.toString()
    }

    override suspend fun <T> transaction(block: suspend () -> T): T = block()
    override suspend fun dirty() = dirty.map { (k, deleted) -> SyncDirty(k.first, k.second, deleted) }
    override suspend fun clearDirty(entity: String, uuid: String) {
        dirty.remove(entity to uuid)
    }

    override suspend fun read(entity: String, uuid: String) = records[entity to uuid]
    override suspend fun write(entity: String, uuid: String, fields: JsonObject) {
        val key = entity to uuid
        records[key] = JsonObject((records[key] ?: JsonObject(emptyMap())) + fields)
        dirty[key] = false
    }

    override suspend fun delete(entity: String, uuid: String) = remove(entity, uuid)
    override suspend fun hasPersonalHistory(entity: String, uuid: String) = (entity to uuid) in personalHistory
    override suspend fun detach(entity: String, uuid: String) {
        detached += entity to uuid
        records[entity to uuid] = JsonObject(records.getValue(entity to uuid) + ("ownership" to json("NOT_OWNED")))
    }

    override suspend fun shadow(entity: String, uuid: String) = shadows[entity to uuid]
    override suspend fun setShadow(entity: String, uuid: String, data: String) {
        shadows[entity to uuid] = data
    }

    override suspend fun clocks(entity: String, uuid: String): Map<String, String> = clocks[entity to uuid].orEmpty()
    override suspend fun setClock(entity: String, uuid: String, field: String, hlc: String) {
        clocks.getOrPut(entity to uuid) { mutableMapOf() }[field] = hlc
    }

    override suspend fun removeClocks(entity: String, uuid: String) {
        clocks.remove(entity to uuid)
    }

    override suspend fun appendOutbox(entity: String, uuid: String, fields: String, hlc: String, deleted: Boolean): Long {
        val seq = outbox.size + 1L
        outbox += SyncChange(seq, entity, uuid, kotlinx.serialization.json.Json.parseToJsonElement(fields) as JsonObject, hlc, deleted)
        return seq
    }

    override suspend fun outboxAfter(seq: Long) = outbox.filter { it.seq > seq }
    override suspend fun afterApply(touched: Set<Pair<String, String>>) = Unit

    /** updatedAt per record voor het eerste samenvoegen; standaard 0. */
    val updatedAt = mutableMapOf<Pair<String, String>, Long>()

    override suspend fun snapshot() = records.map { (k, v) -> SnapshotRecord(k.first, k.second, v, updatedAt[k] ?: 0) }

    override suspend fun rename(entity: String, from: String, to: String) {
        records.remove(entity to from)?.let { records[entity to to] = it }
        updatedAt.remove(entity to from)?.let { updatedAt[entity to to] = it }
        // Verwijzingen gaan in de echte database via lokale id's; hier de uuid's in andere records bijwerken.
        records.replaceAll { _, fields ->
            JsonObject(fields.mapValues { (_, value) ->
                when (value) {
                    is kotlinx.serialization.json.JsonPrimitive -> if (value.isString && value.content == from) kotlinx.serialization.json.JsonPrimitive(to) else value
                    is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(value.map {
                        if (it is kotlinx.serialization.json.JsonPrimitive && it.isString && it.content == from) kotlinx.serialization.json.JsonPrimitive(to) else it
                    })
                    else -> value
                }
            })
        }
    }

    override suspend fun restartTracking() {
        outbox.clear(); shadows.clear(); clocks.clear(); dirty.clear()
        records.keys.forEach { dirty[it] = false }
    }

    private fun json(value: Any?): kotlinx.serialization.json.JsonElement = when (value) {
        null -> JsonNull
        is String -> kotlinx.serialization.json.JsonPrimitive(value)
        is Number -> kotlinx.serialization.json.JsonPrimitive(value)
        is Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
        is List<*> -> kotlinx.serialization.json.JsonArray(value.map(::json))
        else -> error("onbekend type")
    }
}
