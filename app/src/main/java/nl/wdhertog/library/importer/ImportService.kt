package nl.wdhertog.library.importer

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import nl.wdhertog.library.data.db.Book
import nl.wdhertog.library.data.db.BookWork
import nl.wdhertog.library.data.db.EnrichStatus
import nl.wdhertog.library.data.db.ImportRun
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.data.db.Loan
import nl.wdhertog.library.data.db.Ownership
import nl.wdhertog.library.data.db.Person
import nl.wdhertog.library.data.db.ReadingGoal
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.db.Work
import nl.wdhertog.library.data.repository.BookRepository
import nl.wdhertog.library.data.repository.LocationRepository
import nl.wdhertog.library.scan.Isbn
import nl.wdhertog.library.util.IsbnLanguage
import java.time.Instant
import java.time.ZoneId

/** Wat er met een geïmporteerd boek gebeurt. */
sealed interface ImportDecision {
    data object Add : ImportDecision
    data object Skip : ImportDecision

    /** In een bestaand boek: alleen lege velden aanvullen, of de gegevens uit het bestand laten voorgaan. */
    data class Merge(val bookId: Long, val overwrite: Boolean) : ImportDecision
}

data class ImportResult(
    val runId: Long,
    val added: Int,
    val updated: Int,
    val skipped: Int,
    /** Boeken met de tag "taal controleren": de taal uit het bestand past niet bij het ISBN. */
    val languageChecks: Int = 0,
)

/**
 * Schrijft geïmporteerde boeken weg. Gegevens gaan via BookRepository.applyMetadata, zodat
 * zelf ingevulde velden altijd blijven staan; leessessies, tags en uitleningen komen erbij
 * zonder dubbelingen, dus opnieuw importeren van een nieuwere export kan zonder problemen.
 */
class ImportService(
    private val db: LibraryDatabase,
    private val books: BookRepository,
    private val locations: LocationRepository,
) {
    private val dao = db.importDao()
    private val bookDao = db.bookDao()
    private val loanDao = db.loanDao()

    fun observeRuns(): Flow<List<ImportRun>> = dao.observeRuns()

    suspend fun existingBooks(): List<ExistingBook> = dao.bookIdentities().map {
        ExistingBook(it.id, it.title, it.authors?.split('\t').orEmpty(), it.isbn13, it.hirtId)
    }

    suspend fun run(
        source: String,
        fileName: String?,
        items: List<Pair<ImportedBook, ImportDecision>>,
        goals: Map<Int, Int> = emptyMap(),
        onProgress: (done: Int) -> Unit = {},
    ): ImportResult {
        val run = ImportRun(source = source, fileName = fileName).let { it.copy(id = dao.insertRun(it)) }
        val runId = run.id
        var added = 0
        var updated = 0
        var languageChecks = 0
        // Twee regels met hetzelfde ISBN in één bestand (bijv. twee keer gelezen): één boek, aangevuld.
        val addedByIsbn = mutableMapOf<String, Long>()
        items.forEachIndexed { index, (imported, original) ->
            val decision = if (original == ImportDecision.Add) {
                imported.isbn13?.let(addedByIsbn::get)?.let { ImportDecision.Merge(it, overwrite = false) } ?: original
            } else {
                original
            }
            // Taal uit het bestand die het ISBN tegenspreekt (bijv. Engels ISBN met "nl"): markeren voor controle.
            val flag = decision != ImportDecision.Skip && IsbnLanguage.conflicts(imported.isbn13, imported.language)
            val book = if (flag) imported.copy(tags = imported.tags + IsbnLanguage.CHECK_TAG) else imported
            if (flag) languageChecks++
            db.withTransaction {
                when (decision) {
                    ImportDecision.Add -> add(runId, source, book).also { id ->
                        added++
                        book.isbn13?.let { addedByIsbn[it] = id }
                    }
                    is ImportDecision.Merge -> merge(decision, source, book).also { updated++ }
                    ImportDecision.Skip -> Unit
                }
            }
            onProgress(index + 1)
        }
        goals.forEach { (year, target) -> if (dao.goal(year) == null) dao.upsertGoal(ReadingGoal(year, target)) }
        dao.updateRun(run.copy(added = added, updated = updated))
        return ImportResult(runId, added, updated, skipped = items.size - added - updated, languageChecks = languageChecks)
    }

    /** Haalt de boeken weg die deze import heeft toegevoegd; aanvullingen op bestaande boeken blijven. */
    suspend fun undo(runId: Long) {
        dao.booksOfRun(runId).forEach { books.delete(it) }
        loanDao.deleteUnusedPersons()
        dao.deleteRun(runId)
    }

    private suspend fun add(runId: Long, source: String, imported: ImportedBook): Long {
        val bookId = bookDao.insertBook(
            Book(
                isbn13 = imported.isbn13,
                isbn10 = imported.isbn13?.let(Isbn::toIsbn10),
                title = imported.title,
                tags = imported.tags.joinToString(", ").ifEmpty { null },
                notes = imported.notes,
                ownership = if (imported.owned) Ownership.OWNED else Ownership.NOT_OWNED,
                disposalReason = imported.disposalReason.takeIf { !imported.owned },
                disposedTo = imported.disposedTo.takeIf { !imported.owned },
                disposedAt = imported.disposedAt.takeIf { !imported.owned },
                favorite = imported.favorite,
                owner = imported.owner,
                wantToRead = imported.wantToRead,
                // Met ISBN gaat het boek in de verrijkingswachtrij; die vult alleen lege velden aan.
                enrichStatus = if (imported.isbn13 != null) EnrichStatus.NEW else EnrichStatus.MANUAL,
                hirtId = imported.hirtId,
                storygraphId = imported.storygraphId,
                importRunId = runId,
                addedAt = imported.addedAt?.atStartOfDay(ZoneId.systemDefault())?.toInstant() ?: Instant.now(),
            ),
        )
        val workId = bookDao.insertWork(Work(title = imported.originalTitle ?: imported.title, awards = imported.awards))
        bookDao.insertBookWork(BookWork(bookId, workId, position = 0))
        books.applyMetadata(bookId, imported.toMetadata(source), refresh = false, status = null)
        addReadings(bookId, imported, overwrite = false)
        addLoan(bookId, imported)
        imported.locationPath?.let { locations.findOrCreatePath(it) }?.let { locations.moveBook(bookId, it) }
        return bookId
    }

    private suspend fun merge(decision: ImportDecision.Merge, source: String, imported: ImportedBook) {
        val book = bookDao.getBook(decision.bookId) ?: return
        val importedAdded = imported.addedAt?.atStartOfDay(ZoneId.systemDefault())?.toInstant()
        bookDao.updateBook(
            book.copy(
                tags = (book.tags?.split(',').orEmpty().map(String::trim) + imported.tags)
                    .filter(String::isNotEmpty).distinctBy(String::lowercase).joinToString(", ").ifEmpty { null },
                wantToRead = book.wantToRead || imported.wantToRead,
                favorite = book.favorite || imported.favorite,
                owner = if (decision.overwrite) imported.owner ?: book.owner else book.owner ?: imported.owner,
                notes = when {
                    imported.notes == null || book.notes?.contains(imported.notes) == true -> book.notes
                    book.notes == null || decision.overwrite -> imported.notes
                    else -> "${book.notes}\n${imported.notes}"
                },
                hirtId = imported.hirtId ?: book.hirtId,
                storygraphId = book.storygraphId ?: imported.storygraphId,
                // Have I Read That weet vaak al jaren eerder dat je het boek hebt.
                addedAt = if (importedAdded != null && importedAdded < book.addedAt) importedAdded else book.addedAt,
                updatedAt = Instant.now(),
            ),
        )
        bookDao.getPrimaryWork(book.id)?.let { work ->
            if (imported.awards != null && (decision.overwrite || work.awards == null)) {
                bookDao.updateWork(work.copy(awards = imported.awards, updatedAt = Instant.now()))
            }
        }
        books.applyMetadata(book.id, imported.toMetadata(source), refresh = decision.overwrite, status = null)
        addReadings(book.id, imported, decision.overwrite)
        addLoan(book.id, imported)
        // Een plek die je zelf al had gekozen, blijft staan.
        if (book.locationId == null) imported.locationPath?.let { locations.findOrCreatePath(it) }?.let { locations.moveBook(book.id, it) }
    }

    /** Sessies met dezelfde datums (of allebei zonder datum, zelfde status) zijn dezelfde. */
    private suspend fun addReadings(bookId: Long, imported: ImportedBook, overwrite: Boolean) {
        val existing = dao.sessionsForBook(bookId).toMutableList()
        imported.readings.forEach { reading ->
            val same = existing.firstOrNull { session ->
                session.startedAt == reading.startedAt && session.finishedAt == reading.finishedAt &&
                    (reading.startedAt != null || reading.finishedAt != null || session.status == reading.status)
            }
            if (same == null) {
                dao.insertSession(
                    ReadingSession(
                        bookId = bookId,
                        startedAt = reading.startedAt,
                        finishedAt = reading.finishedAt,
                        datePrecision = reading.datePrecision,
                        status = reading.status,
                        rating = reading.rating,
                        review = reading.review,
                        progressPage = reading.progressPage,
                        progressPercent = reading.progressPercent,
                    ),
                )
            } else {
                existing.remove(same)
                val rating = if (overwrite) reading.rating ?: same.rating else same.rating ?: reading.rating
                val review = if (overwrite) reading.review ?: same.review else same.review ?: reading.review
                // Voortgang uit een nieuwere export is altijd actueler.
                val progressPage = reading.progressPage ?: same.progressPage
                val progressPercent = reading.progressPercent ?: same.progressPercent
                if (rating != same.rating || review != same.review || progressPage != same.progressPage || progressPercent != same.progressPercent) {
                    dao.updateSession(
                        same.copy(
                            rating = rating,
                            review = review,
                            progressPage = progressPage,
                            progressPercent = progressPercent,
                            updatedAt = Instant.now(),
                        ),
                    )
                }
            }
        }
    }

    /**
     * "Uitgeleend" uit het bestand wordt een open uitlening, tenzij het boek al eens is
     * uitgeleend (dan is die uitlening al bekend of al teruggekomen).
     */
    private suspend fun addLoan(bookId: Long, imported: ImportedBook) {
        val lentTo = imported.lentTo ?: return
        if (!imported.owned || loanDao.loanCount(bookId) > 0) return
        val personId = lentTo.trim().ifEmpty { null }?.let { name ->
            loanDao.findPerson(name)?.id ?: loanDao.insertPerson(Person(name = name))
        }
        loanDao.insertLoan(Loan(bookId = bookId, personId = personId))
    }
}
