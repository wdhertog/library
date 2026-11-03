package nl.wdhertog.library.data.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.wdhertog.library.data.sync.SyncSchema.BOOK
import nl.wdhertog.library.data.sync.SyncSchema.LOAN
import nl.wdhertog.library.data.sync.SyncSchema.LOCATION
import nl.wdhertog.library.data.sync.SyncSchema.PERSON
import nl.wdhertog.library.data.sync.SyncSchema.SERIES
import nl.wdhertog.library.data.sync.SyncSchema.WORK
import nl.wdhertog.library.importer.ExistingBook
import nl.wdhertog.library.importer.ImportMatcher
import nl.wdhertog.library.importer.ImportedBook
import nl.wdhertog.library.importer.MatchReason
import java.util.Locale

/** Eén gedeeld record zoals het bij het eerste samenvoegen wordt doorgegeven. */
@Serializable
data class SnapshotRecord(val entity: String, val uuid: String, val fields: JsonObject, val updatedAt: Long)

/** Een boek zoals het in het controlescherm staat. */
data class MergeBook(val uuid: String, val title: String, val authors: List<String>, val isbn13: String?)

/** Twee boeken die hetzelfde lijken: [local] op deze telefoon, [remote] op de andere. */
data class MergePair(val local: MergeBook, val remote: MergeBook, val reason: MatchReason) {
    /** Zekere overeenkomsten staan standaard aan, twijfelgevallen uit. */
    val certain get() = reason != MatchReason.OTHER_ISBN
}

/** Wat het eerste samenvoegen gaat doen; de boekparen legt het controlescherm voor. */
data class MergePlan(
    val pairs: List<MergePair>,
    /** Boeken die al dezelfde uuid hebben (bijv. weer koppelen na het terugzetten van een back-up). */
    val alreadyShared: Int,
    val onlyLocal: Int,
    val onlyRemote: Int,
    /** Plekken (op pad), series en personen (op naam): altijd samen. Sleutel: soort en lokale uuid. */
    internal val byName: Map<Pair<String, String>, String>,
)

/**
 * Het eerste samenvoegen van twee bibliotheken (ontwerp §11, S4), op de telefoon die koppelt en
 * nog met geen enkele andere telefoon gekoppeld is:
 *
 * 1. [plan]: overeenkomsten zoeken. Boeken zoals bij importeren (ISBN, anders titel + auteur;
 *    zelfde titel met een ander ISBN is een twijfelgeval), plekken op pad, series en personen op naam.
 * 2. [renames]: na het controlescherm; werken en uitleningen van samengevoegde boeken gaan mee.
 * 3. [apply]: lokale records krijgen de uuid van de andere telefoon. De schaduwkopie wordt zo gezet
 *    dat de gewone uitwisseling per veld het juiste doet: een ingevuld veld wint van een leeg veld,
 *    en bij een verschil wint het record dat het laatst gewijzigd is.
 */
object FirstMerge {

    fun plan(local: List<SnapshotRecord>, remote: List<SnapshotRecord>): MergePlan {
        val localBooks = books(local)
        val remoteBooks = books(remote)
        val remoteUuids = remoteBooks.map { it.uuid }.toSet()
        val shared = localBooks.count { it.uuid in remoteUuids }
        val localRest = localBooks.filter { it.uuid !in remoteUuids }
        val localUuids = localBooks.map { it.uuid }.toSet()
        val remoteRest = remoteBooks.filter { it.uuid !in localUuids }

        val existing = localRest.mapIndexed { index, b -> ExistingBook(index.toLong(), b.title, b.authors, b.isbn13, null) }
        val matches = ImportMatcher.match(remoteRest.map { ImportedBook(title = it.title, authors = it.authors, isbn13 = it.isbn13) }, existing)
        val pairs = remoteRest.zip(matches).mapNotNull { (remoteBook, match) ->
            match?.let { MergePair(localRest[it.existing.id.toInt()], remoteBook, it.reason) }
        }

        val byName = mutableMapOf<Pair<String, String>, String>()
        fun link(entity: String, key: (SnapshotRecord, List<SnapshotRecord>) -> String?) {
            val theirs = remote.filter { it.entity == entity }.groupBy { key(it, remote) }
            val taken = mutableSetOf<String>()
            local.filter { it.entity == entity }.forEach { mine ->
                if (remote.any { it.entity == entity && it.uuid == mine.uuid }) return@forEach
                val match = key(mine, local)?.let { theirs[it] }?.firstOrNull { it.uuid !in taken } ?: return@forEach
                taken += match.uuid
                byName[entity to mine.uuid] = match.uuid
            }
        }
        link(LOCATION) { record, all -> path(record, all) }
        link(SERIES) { record, _ -> normalize(record.fields.str("name")) }
        link(PERSON) { record, _ -> normalize(record.fields.str("name")) }

        return MergePlan(pairs, shared, localBooks.size - shared - pairs.size, remoteBooks.size - shared - pairs.size, byName)
    }

    /**
     * Alle hernoemingen (soort en lokale uuid → uuid op de andere telefoon) voor de boeken die in het
     * controlescherm als hetzelfde zijn aangemerkt, met hun werken en uitleningen.
     */
    fun renames(
        plan: MergePlan,
        accepted: Set<String>,
        local: List<SnapshotRecord>,
        remote: List<SnapshotRecord>,
    ): Map<Pair<String, String>, String> {
        val result = plan.byName.toMutableMap()
        val targets = result.values.toMutableSet()
        val localUuids = local.map { it.entity to it.uuid }.toSet()
        fun add(entity: String, from: String, to: String) {
            if (from == to || (entity to from) in result || to in targets || (entity to to) in localUuids) return
            result[entity to from] = to
            targets += to
        }
        val localByUuid = local.associateBy { it.entity to it.uuid }
        val remoteByUuid = remote.associateBy { it.entity to it.uuid }

        plan.pairs.filter { it.local.uuid in accepted }.forEach { pair ->
            add(BOOK, pair.local.uuid, pair.remote.uuid)
            val mine = localByUuid[BOOK to pair.local.uuid]?.fields?.list("works").orEmpty()
            val theirs = remoteByUuid[BOOK to pair.remote.uuid]?.fields?.list("works").orEmpty()
            // Evenveel werken: op volgorde; anders alleen het eerste (het hoofdwerk).
            val linked = if (mine.size == theirs.size) mine.zip(theirs) else listOfNotNull(mine.firstOrNull()?.let { m -> theirs.firstOrNull()?.let { m to it } })
            linked.forEach { (from, to) -> add(WORK, from, to) }
        }

        // Uitleningen: zelfde boek, zelfde persoon, zelfde datum.
        fun mapped(entity: String, uuid: String?) = uuid?.let { result[entity to it] ?: it }
        val theirLoans = remote.filter { it.entity == LOAN }.groupBy { loanKey(it.fields.str("book"), it.fields.str("person"), it.fields.str("lentAt")) }
        local.filter { it.entity == LOAN }.forEach { loan ->
            val key = loanKey(mapped(BOOK, loan.fields.str("book")), mapped(PERSON, loan.fields.str("person")), loan.fields.str("lentAt"))
            theirLoans[key]?.firstOrNull { it.uuid !in targets }?.let { add(LOAN, loan.uuid, it.uuid) }
        }
        return result
    }

    /**
     * Voert de hernoemingen uit en zet alles klaar voor de eerste uitwisseling. Daarna: de gewone sessie.
     * [remoteClock] is de klok van de andere telefoon, zodat wat hier wint ook een latere tijd krijgt.
     */
    suspend fun apply(
        store: SyncStore,
        clock: HybridClock,
        renames: Map<Pair<String, String>, String>,
        local: List<SnapshotRecord>,
        remote: List<SnapshotRecord>,
        remoteClock: String,
    ) = store.transaction {
        renames.forEach { (key, to) -> store.rename(key.first, key.second, to) }
        store.restartTracking()
        clock.receive(HybridClock.Timestamp.parse(remoteClock))

        val localUpdated = local.associate { (it.entity to (renames[it.entity to it.uuid] ?: it.uuid)) to it.updatedAt }
        for (theirs in remote) {
            val key = theirs.entity to theirs.uuid
            val updated = localUpdated[key] ?: continue
            val mine = store.read(theirs.entity, theirs.uuid) ?: continue
            val theirsNewer = theirs.updatedAt > updated
            val shadow = SyncSchema.fields.getValue(theirs.entity).associateWith { field ->
                val m = mine[field] ?: JsonNull
                val t = theirs.fields[field] ?: JsonNull
                when {
                    m == t -> m
                    // De schaduw gelijk aan de eigen waarde: niet versturen, en zonder eigen veldklok wint wat binnenkomt.
                    isEmpty(m) -> m
                    // De schaduw gelijk aan die van de ander: het eigen veld gaat mee, met een latere tijd.
                    isEmpty(t) -> t
                    theirsNewer -> m
                    else -> t
                }
            }
            store.setShadow(theirs.entity, theirs.uuid, JsonObject(shadow).toString())
        }
    }

    // --- Hulp ---

    private fun books(records: List<SnapshotRecord>): List<MergeBook> {
        val works = records.filter { it.entity == WORK }.associateBy { it.uuid }
        return records.filter { it.entity == BOOK }.map { book ->
            val authors = book.fields.list("works").flatMap { works[it]?.fields?.list("authors").orEmpty() }.distinct()
            MergeBook(book.uuid, book.fields.str("title").orEmpty(), authors, book.fields.str("isbn13"))
        }
    }

    private fun path(record: SnapshotRecord, all: List<SnapshotRecord>): String? {
        val byUuid = all.filter { it.entity == LOCATION }.associateBy { it.uuid }
        val names = mutableListOf<String>()
        var current: SnapshotRecord? = record
        while (current != null && names.size < 20) {
            names += normalize(current.fields.str("name")) ?: return null
            current = current.fields.str("parent")?.let(byUuid::get)
        }
        return names.reversed().joinToString(" › ")
    }

    private fun normalize(text: String?) = text?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty)

    private fun loanKey(book: String?, person: String?, lentAt: String?) = "$book|$person|$lentAt"

    private fun isEmpty(value: JsonElement) = when (value) {
        is JsonNull -> true
        is JsonPrimitive -> value.isString && value.content.isBlank()
        is JsonArray -> value.isEmpty()
        else -> false
    }

    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.list(name: String): List<String> = (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
}
