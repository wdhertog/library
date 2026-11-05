package nl.wdhertog.library.data.sync

import androidx.room.withTransaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import nl.wdhertog.library.covers.CoverStore
import nl.wdhertog.library.data.db.Book
import nl.wdhertog.library.data.db.BookFormat
import nl.wdhertog.library.data.db.BookWork
import nl.wdhertog.library.data.db.DisposalReason
import nl.wdhertog.library.data.db.EnrichStatus
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.data.db.Loan
import nl.wdhertog.library.data.db.Location
import nl.wdhertog.library.data.db.LocationType
import nl.wdhertog.library.data.db.Ownership
import nl.wdhertog.library.data.db.Person
import nl.wdhertog.library.data.db.Series
import nl.wdhertog.library.data.db.Work
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.data.sync.SyncSchema.BOOK
import nl.wdhertog.library.data.sync.SyncSchema.LOAN
import nl.wdhertog.library.data.sync.SyncSchema.LOCATION
import nl.wdhertog.library.data.sync.SyncSchema.PERSON
import nl.wdhertog.library.data.sync.SyncSchema.SERIES
import nl.wdhertog.library.data.sync.SyncSchema.WORK
import java.time.Instant
import java.time.LocalDate

/**
 * SyncStore op de echte database: vertaalt tussen de tabellen (met lokale id's) en de vorm waarin
 * records uitgewisseld worden (uuid's, lijsten voor koppeltabellen). Persoonlijke velden
 * (favoriet, wil ik lezen) worden nooit gelezen of geschreven.
 */
class RoomSyncStore(private val db: LibraryDatabase, private val books: BookRepository) : SyncStore {
    private val dao = db.syncDao()
    private val bookDao = db.bookDao()

    override suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }

    override suspend fun dirty() = dao.dirty()
    override suspend fun clearDirty(entity: String, uuid: String) = dao.clearDirty(entity, uuid)

    // --- Lezen ---

    override suspend fun read(entity: String, uuid: String): JsonObject? = when (entity) {
        BOOK -> dao.book(uuid)?.let { b ->
            obj(
                "isbn13" to b.isbn13, "isbn10" to b.isbn10, "title" to b.title, "subtitle" to b.subtitle,
                "language" to b.language, "translators" to b.translators, "publisher" to b.publisher,
                "publishedYear" to b.publishedYear, "pages" to b.pages, "format" to b.format?.name,
                "description" to b.description, "coverUrl" to b.coverUrl, "coverFile" to b.coverFile, "tags" to b.tags,
                "ownership" to b.ownership.name, "borrowedFrom" to b.borrowedFromPersonId?.let { dao.personUuid(it) },
                "borrowedDueDate" to b.borrowedDueDate?.toString(), "disposalReason" to b.disposalReason?.name,
                "disposedTo" to b.disposedTo, "disposedAt" to b.disposedAt?.toString(), "owner" to b.owner,
                "location" to b.locationId?.let { dao.locationUuid(it) }, "shelfPosition" to b.shelfPosition,
                "notes" to b.notes, "enrichStatus" to b.enrichStatus.name, "enrichedSources" to b.enrichedSources,
                "lockedFields" to b.lockedFields, "openLibraryEditionId" to b.openLibraryEditionId,
                "storygraphId" to b.storygraphId, "hirtId" to b.hirtId, "addedAt" to b.addedAt.toEpochMilli(),
                "works" to dao.workUuidsOfBook(b.id),
            )
        }
        WORK -> dao.work(uuid)?.let { w ->
            obj(
                "title" to w.title, "originalTitle" to w.originalTitle, "originalLanguage" to w.originalLanguage,
                "firstPublishedYear" to w.firstPublishedYear, "series" to w.seriesId?.let { dao.seriesUuid(it) },
                "seriesIndex" to w.seriesIndex, "awards" to w.awards, "openLibraryWorkId" to w.openLibraryWorkId,
                "wikidataId" to w.wikidataId, "authors" to bookDao.getAuthorNames(w.id),
            )
        }
        SERIES -> dao.series(uuid)?.let {
            obj("name" to it.name, "totalVolumes" to it.totalVolumes, "wikidataId" to it.wikidataId, "manualParts" to it.manualParts)
        }
        LOCATION -> dao.location(uuid)?.let { l ->
            obj("parent" to l.parentId?.let { dao.locationUuid(it) }, "name" to l.name, "type" to l.type.name, "sortOrder" to l.sortOrder)
        }
        PERSON -> dao.person(uuid)?.let { obj("name" to it.name, "contactUri" to it.contactUri) }
        LOAN -> dao.loan(uuid)?.let { l ->
            obj(
                "book" to dao.bookUuid(l.bookId), "person" to l.personId?.let { dao.personUuid(it) },
                "lentAt" to l.lentAt?.toString(), "dueAt" to l.dueAt?.toString(), "returnedAt" to l.returnedAt?.toString(),
                "note" to l.note,
            )
        }
        else -> null
    }

    // --- Schrijven ---

    override suspend fun write(entity: String, uuid: String, fields: JsonObject) {
        val f = Fields(fields)
        val now = Instant.now()
        when (entity) {
            BOOK -> {
                val existing = dao.book(uuid)
                var b = existing ?: Book(uuid = uuid, title = "")
                b = b.copy(
                    isbn13 = f.str("isbn13", b.isbn13), isbn10 = f.str("isbn10", b.isbn10), title = f.str("title", b.title) ?: "",
                    subtitle = f.str("subtitle", b.subtitle), language = f.str("language", b.language),
                    translators = f.str("translators", b.translators), publisher = f.str("publisher", b.publisher),
                    publishedYear = f.int("publishedYear", b.publishedYear), pages = f.int("pages", b.pages),
                    format = f.enum("format", b.format) { BookFormat.valueOf(it) }, description = f.str("description", b.description),
                    coverUrl = f.str("coverUrl", b.coverUrl), coverFile = f.str("coverFile", b.coverFile).let { if (it == null || CoverStore.isValidName(it)) it else b.coverFile }, tags = f.str("tags", b.tags),
                    ownership = f.enum("ownership", b.ownership) { Ownership.valueOf(it) } ?: Ownership.OWNED,
                    borrowedFromPersonId = f.ref("borrowedFrom", b.borrowedFromPersonId) { dao.person(it)?.id },
                    borrowedDueDate = f.date("borrowedDueDate", b.borrowedDueDate),
                    disposalReason = f.enum("disposalReason", b.disposalReason) { DisposalReason.valueOf(it) },
                    disposedTo = f.str("disposedTo", b.disposedTo), disposedAt = f.date("disposedAt", b.disposedAt),
                    owner = f.str("owner", b.owner), locationId = f.ref("location", b.locationId) { dao.location(it)?.id },
                    shelfPosition = f.int("shelfPosition", b.shelfPosition), notes = f.str("notes", b.notes),
                    enrichStatus = f.enum("enrichStatus", b.enrichStatus) { EnrichStatus.valueOf(it) } ?: EnrichStatus.NEW,
                    enrichedSources = f.str("enrichedSources", b.enrichedSources), lockedFields = f.str("lockedFields", b.lockedFields),
                    openLibraryEditionId = f.str("openLibraryEditionId", b.openLibraryEditionId),
                    storygraphId = f.str("storygraphId", b.storygraphId), hirtId = f.long("hirtId", b.hirtId),
                    addedAt = f.long("addedAt", b.addedAt.toEpochMilli())?.let(Instant::ofEpochMilli) ?: b.addedAt,
                    updatedAt = now,
                )
                val id = if (existing == null) bookDao.insertBook(b) else b.id.also { bookDao.updateBook(b) }
                // Een vervangen eigen foto opruimen; de nieuwe komt aan het eind van de sessie mee.
                existing?.coverFile?.takeIf { it != b.coverFile }?.let { books.deleteCoverIfUnused(it) }
                f.list("works")?.let { workUuids ->
                    val ids = dao.workIdsByUuid(workUuids).associate { it.uuid to it.id }
                    dao.clearBookWorks(id)
                    workUuids.mapNotNull(ids::get).forEachIndexed { position, workId -> bookDao.insertBookWork(BookWork(id, workId, position)) }
                }
            }
            WORK -> {
                val existing = dao.work(uuid)
                var w = existing ?: Work(uuid = uuid, title = "")
                w = w.copy(
                    title = f.str("title", w.title) ?: "", originalTitle = f.str("originalTitle", w.originalTitle),
                    originalLanguage = f.str("originalLanguage", w.originalLanguage),
                    firstPublishedYear = f.int("firstPublishedYear", w.firstPublishedYear),
                    seriesId = f.ref("series", w.seriesId) { dao.series(it)?.id }, seriesIndex = f.str("seriesIndex", w.seriesIndex),
                    awards = f.str("awards", w.awards), openLibraryWorkId = f.str("openLibraryWorkId", w.openLibraryWorkId),
                    wikidataId = f.str("wikidataId", w.wikidataId), updatedAt = now,
                )
                val id = if (existing == null) bookDao.insertWork(w) else w.id.also { bookDao.updateWork(w) }
                f.list("authors")?.let { books.setWorkAuthors(id, it) }
            }
            SERIES -> {
                val existing = dao.series(uuid)
                val s = (existing ?: Series(uuid = uuid, name = "")).let {
                    it.copy(name = f.str("name", it.name) ?: "", totalVolumes = f.int("totalVolumes", it.totalVolumes),
                        wikidataId = f.str("wikidataId", it.wikidataId), manualParts = f.str("manualParts", it.manualParts), updatedAt = now)
                }
                if (existing == null) dao.insertSeries(s) else dao.updateSeries(s)
            }
            LOCATION -> {
                val existing = dao.location(uuid)
                val l = (existing ?: Location(uuid = uuid, name = "")).let {
                    it.copy(parentId = f.ref("parent", it.parentId) { p -> dao.location(p)?.id }, name = f.str("name", it.name) ?: "",
                        type = f.enum("type", it.type) { t -> LocationType.valueOf(t) } ?: LocationType.OTHER,
                        sortOrder = f.int("sortOrder", it.sortOrder) ?: 0, updatedAt = now)
                }
                if (existing == null) db.locationDao().insert(l) else db.locationDao().update(l)
            }
            PERSON -> {
                val existing = dao.person(uuid)
                val p = (existing ?: Person(uuid = uuid, name = "")).let {
                    it.copy(name = f.str("name", it.name) ?: "", contactUri = f.str("contactUri", it.contactUri), updatedAt = now)
                }
                if (existing == null) dao.insertPerson(p) else dao.updatePerson(p)
            }
            LOAN -> {
                val existing = dao.loan(uuid)
                val bookId = f.ref("book", existing?.bookId) { dao.book(it)?.id } ?: return // zonder boek geen uitlening
                val l = (existing ?: Loan(uuid = uuid, bookId = bookId)).let {
                    it.copy(bookId = bookId, personId = f.ref("person", it.personId) { p -> dao.person(p)?.id },
                        lentAt = f.date("lentAt", it.lentAt), dueAt = f.date("dueAt", it.dueAt),
                        returnedAt = f.date("returnedAt", it.returnedAt), note = f.str("note", it.note), updatedAt = now)
                }
                if (existing == null) dao.insertLoan(l) else dao.updateLoan(l)
            }
        }
    }

    override suspend fun delete(entity: String, uuid: String) {
        when (entity) {
            BOOK -> dao.book(uuid)?.let { books.delete(it.id) }
            WORK -> dao.work(uuid)?.let { dao.deleteWork(it.id) }
            SERIES -> dao.series(uuid)?.let { dao.deleteSeries(it.id) }
            LOCATION -> dao.location(uuid)?.let { db.locationDao().delete(it.id) }
            PERSON -> dao.person(uuid)?.let { dao.deletePerson(it.id) }
            LOAN -> dao.loan(uuid)?.let { db.loanDao().deleteLoan(it.id) }
        }
    }

    override suspend fun hasPersonalHistory(entity: String, uuid: String): Boolean =
        entity == BOOK && dao.book(uuid)?.let { dao.readingSessionCount(it.id) > 0 } == true

    override suspend fun detach(entity: String, uuid: String) {
        val book = dao.book(uuid) ?: return
        bookDao.updateBook(book.copy(ownership = Ownership.NOT_OWNED, locationId = null, shelfPosition = null, updatedAt = Instant.now()))
    }

    // --- Schaduw, klokken, outbox ---

    override suspend fun shadow(entity: String, uuid: String) = dao.shadow(entity, uuid)
    override suspend fun setShadow(entity: String, uuid: String, data: String) = dao.setShadow(SyncShadow(entity, uuid, data))
    override suspend fun clocks(entity: String, uuid: String) = dao.clocks(entity, uuid).associate { it.field to it.hlc }
    override suspend fun setClock(entity: String, uuid: String, field: String, hlc: String) =
        dao.setClock(SyncFieldClock(entity, uuid, field, hlc))
    override suspend fun removeClocks(entity: String, uuid: String) = dao.removeClocks(entity, uuid)

    override suspend fun appendOutbox(entity: String, uuid: String, fields: String, hlc: String, deleted: Boolean) =
        dao.appendOutbox(SyncOutbox(entity = entity, uuid = uuid, fields = fields, hlc = hlc, deleted = deleted))

    override suspend fun outboxAfter(seq: Long) = dao.outboxAfter(seq).map {
        SyncChange(it.seq, it.entity, it.uuid, Json.parseToJsonElement(it.fields).jsonObject, it.hlc, it.deleted)
    }

    override suspend fun afterApply(touched: Set<Pair<String, String>>) {
        books.deleteOrphans()
        val bookIds = touched.mapNotNull { (entity, uuid) ->
            when (entity) {
                BOOK -> dao.book(uuid)?.id?.let(::listOf)
                WORK -> dao.work(uuid)?.id?.let { bookDao.bookIdsOfWork(it) }
                else -> null
            }
        }.flatten().distinct()
        bookIds.forEach { books.refreshSearchText(it) }
    }

    // --- Eerste keer samenvoegen ---

    override suspend fun snapshot(): List<SnapshotRecord> = listOf(
        BOOK to dao.timesBook(), WORK to dao.timesWork(), SERIES to dao.timesSeries(),
        LOCATION to dao.timesLocation(), PERSON to dao.timesPerson(), LOAN to dao.timesLoan(),
    ).flatMap { (entity, times) ->
        times.mapNotNull { t -> read(entity, t.uuid)?.let { SnapshotRecord(entity, t.uuid, it, t.updatedAt) } }
    }

    override suspend fun rename(entity: String, from: String, to: String) = when (entity) {
        BOOK -> dao.renameBook(from, to)
        WORK -> dao.renameWork(from, to)
        SERIES -> dao.renameSeries(from, to)
        LOCATION -> dao.renameLocation(from, to)
        PERSON -> dao.renamePerson(from, to)
        LOAN -> dao.renameLoan(from, to)
        else -> Unit
    }

    override suspend fun restartTracking() {
        dao.clearOutbox()
        dao.clearShadows()
        dao.clearClocks()
        dao.clearAllDirty()
        SyncRepository.markAll(dao)
    }

    // --- Hulp bij omzetten ---

    private fun obj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(pairs.associate { (k, v) -> k to toJson(v) })

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is List<*> -> JsonArray(value.map(::toJson))
        else -> error("Niet uit te wisselen: ${value::class}")
    }

    /** Binnengekomen velden; een ontbrekend veld houdt de huidige waarde. */
    private class Fields(private val json: JsonObject) {
        private fun value(name: String) = json[name] as? JsonPrimitive
        private fun has(name: String) = name in json

        fun str(name: String, current: String?): String? = if (has(name)) value(name)?.contentOrNull else current
        fun int(name: String, current: Int?): Int? = if (has(name)) value(name)?.intOrNull else current
        fun long(name: String, current: Long?): Long? = if (has(name)) value(name)?.longOrNull else current
        fun date(name: String, current: LocalDate?): LocalDate? = if (has(name)) value(name)?.contentOrNull?.let(LocalDate::parse) else current
        fun <E> enum(name: String, current: E?, parse: (String) -> E): E? =
            if (has(name)) value(name)?.contentOrNull?.let { runCatching { parse(it) }.getOrNull() } else current
        suspend fun ref(name: String, current: Long?, resolve: suspend (String) -> Long?): Long? =
            if (has(name)) value(name)?.contentOrNull?.let { resolve(it) } else current
        fun list(name: String): List<String>? = (json[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }
}
