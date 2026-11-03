package nl.wdhertog.library.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import nl.wdhertog.library.data.db.Author
import nl.wdhertog.library.data.db.Book
import nl.wdhertog.library.data.db.BookWork
import nl.wdhertog.library.data.db.BookWorkRow
import nl.wdhertog.library.data.db.EnrichStatus
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.data.db.Series
import nl.wdhertog.library.data.db.Work
import nl.wdhertog.library.data.db.WorkAuthor
import nl.wdhertog.library.covers.CoverStore
import nl.wdhertog.library.lookup.BookMetadata
import nl.wdhertog.library.scan.Isbn
import nl.wdhertog.library.util.TextNormalizer
import java.time.Instant

sealed interface ScanAddResult {
    val bookId: Long

    data class Added(override val bookId: Long) : ScanAddResult
    data class AlreadyPresent(override val bookId: Long) : ScanAddResult
}

class BookRepository(private val db: LibraryDatabase, private val covers: CoverStore) {

    private companion object {
        /** In lockedFields: zelf van andere edities losgekoppeld. */
        const val WORK_LOCK = "work"
    }

    private val dao = db.bookDao()

    fun observeLibrary(): Flow<List<BookListItem>> =
        combine(dao.observeBooks(), dao.observeBookAuthors(), dao.observeBookSeries()) { books, authorRows, seriesRows ->
            val authorsByBook = authorRows.groupBy({ it.bookId }, { it.name })
            val seriesByBook = seriesRows.associateBy { it.bookId }
            books.map { book ->
                BookListItem(
                    book = book,
                    authors = authorsByBook[book.id].orEmpty().distinct(),
                    series = seriesByBook[book.id],
                )
            }
        }

    fun observeDetail(bookId: Long): Flow<BookDetail?> =
        combine(
            dao.observeBook(bookId),
            dao.observeWorksForBook(bookId),
            dao.observeAuthorsForBook(bookId),
            dao.observeSeriesForBook(bookId),
            dao.observeOtherEditions(bookId),
        ) { book, works, authors, series, others ->
            book?.let { BookDetail(it, works, authors.map { row -> row.name }.distinct(), series, others) }
        }

    fun observeAuthorNames(): Flow<List<String>> = dao.observeAuthorNames()

    fun observeSeriesNames(): Flow<List<String>> = dao.observeSeriesNames()

    fun observeAwaitingEnrichmentCount(): Flow<Int> = dao.observeAwaitingEnrichmentCount()

    suspend fun isbnFor(bookId: Long): String? = dao.getBook(bookId)?.isbn13

    suspend fun booksAwaitingEnrichment(limit: Int): List<Long> = dao.booksAwaitingEnrichment(limit)

    suspend fun requeueFailed(): Int = dao.requeueFailed()

    suspend fun markEnrichFailed(bookId: Long) = dao.setEnrichStatus(bookId, EnrichStatus.FAILED, Instant.now())

    suspend fun loadDraft(bookId: Long): BookDraft? {
        val book = dao.getBook(bookId) ?: return null
        val work = dao.getPrimaryWork(bookId)
        val authors = work?.let { dao.getAuthorNames(it.id) }.orEmpty()
        val seriesName = work?.seriesId?.let { dao.getSeries(it)?.name }.orEmpty()
        return BookDraft(
            title = book.title,
            subtitle = book.subtitle.orEmpty(),
            authors = authors.ifEmpty { listOf("") },
            originalTitle = work?.originalTitle.orEmpty(),
            isbn = book.isbn13 ?: book.isbn10.orEmpty(),
            language = book.language.orEmpty(),
            publisher = book.publisher.orEmpty(),
            publishedYear = book.publishedYear?.toString().orEmpty(),
            pages = book.pages?.toString().orEmpty(),
            format = book.format,
            seriesName = seriesName,
            seriesIndex = work?.seriesIndex.orEmpty(),
            tags = book.tags.orEmpty(),
            description = book.description.orEmpty(),
            notes = book.notes.orEmpty(),
            wantToRead = book.wantToRead,
        )
    }

    /** Id van een ander boek met hetzelfde ISBN, of null. */
    suspend fun findDuplicateIsbn(isbn: String, excludeBookId: Long): Long? {
        val isbn13 = Isbn.toIsbn13(isbn) ?: return null
        return dao.findBookIdByIsbn(isbn13, excludeBookId)
    }

    /** Legt een gescand ISBN direct vast; gegevens worden daarna los opgezocht. */
    suspend fun addScannedIsbn(isbn13: String): ScanAddResult = db.withTransaction {
        dao.findBookByIsbn(isbn13)?.let { return@withTransaction ScanAddResult.AlreadyPresent(it.id) }
        val bookId = dao.insertBook(
            Book(isbn13 = isbn13, isbn10 = Isbn.toIsbn10(isbn13), title = "", searchText = isbn13),
        )
        val workId = dao.insertWork(Work(title = ""))
        dao.insertBookWork(BookWork(bookId, workId, position = 0))
        ScanAddResult.Added(bookId)
    }

    /** Slaat een handmatig ingevoerd of bewerkt boek op en geeft het id terug. bookId 0 = nieuw. */
    suspend fun save(bookId: Long, draft: BookDraft): Long = db.withTransaction {
        val now = Instant.now()
        val existing = if (bookId != 0L) dao.getBook(bookId) else null
        val previousDraft = existing?.let { loadDraft(it.id) }
        val locked = existing?.lockedFields.toFieldSet() + draft.changedFields(previousDraft)

        val isbn13 = Isbn.toIsbn13(draft.isbn)
        val series = findOrCreateSeries(draft.seriesName)

        val bookFields = Book(
            isbn13 = isbn13,
            isbn10 = isbn13?.let(Isbn::toIsbn10),
            title = draft.title.trim(),
            subtitle = draft.subtitle.trim().ifEmpty { null },
            language = draft.language.ifEmpty { null },
            publisher = draft.publisher.trim().ifEmpty { null },
            publishedYear = draft.publishedYear.trim().toIntOrNull(),
            pages = draft.pages.trim().toIntOrNull(),
            format = draft.format,
            description = draft.description.trim().ifEmpty { null },
            tags = draft.tags.split(',').map(String::trim).filter(String::isNotEmpty).joinToString(", ").ifEmpty { null },
            notes = draft.notes.trim().ifEmpty { null },
            wantToRead = draft.wantToRead,
            lockedFields = locked.sorted().joinToString(",").ifEmpty { null },
            updatedAt = now,
        )

        val savedBookId = if (existing == null) {
            dao.insertBook(bookFields.copy(enrichStatus = if (isbn13 != null) EnrichStatus.NEW else EnrichStatus.MANUAL))
        } else {
            dao.updateBook(
                bookFields.copy(
                    id = existing.id,
                    uuid = existing.uuid,
                    coverUrl = existing.coverUrl,
                    coverFile = existing.coverFile,
                    translators = existing.translators,
                    ownership = existing.ownership,
                    disposalReason = existing.disposalReason,
                    disposedTo = existing.disposedTo,
                    disposedAt = existing.disposedAt,
                    favorite = existing.favorite,
                    owner = existing.owner,
                    borrowedFromPersonId = existing.borrowedFromPersonId,
                    borrowedDueDate = existing.borrowedDueDate,
                    locationId = existing.locationId,
                    shelfPosition = existing.shelfPosition,
                    enrichStatus = existing.enrichStatus,
                    enrichedSources = existing.enrichedSources,
                    openLibraryEditionId = existing.openLibraryEditionId,
                    storygraphId = existing.storygraphId,
                    hirtId = existing.hirtId,
                    importRunId = existing.importRunId,
                    addedAt = existing.addedAt,
                ),
            )
            existing.id
        }

        val originalTitle = draft.originalTitle.trim().ifEmpty { null }
        val work = existing?.let { dao.getPrimaryWork(it.id) }
        val workId = if (work == null) {
            dao.insertWork(
                Work(
                    title = originalTitle ?: bookFields.title,
                    originalTitle = originalTitle,
                    seriesId = series?.id,
                    seriesIndex = draft.seriesIndex.trim().ifEmpty { null },
                ),
            ).also { dao.insertBookWork(BookWork(savedBookId, it, position = 0)) }
        } else {
            dao.updateWork(
                work.copy(
                    title = originalTitle ?: work.title.ifBlank { bookFields.title },
                    originalTitle = originalTitle,
                    seriesId = series?.id,
                    seriesIndex = draft.seriesIndex.trim().ifEmpty { null },
                    updatedAt = now,
                ),
            )
            work.id
        }

        setWorkAuthors(workId, draft.cleanAuthors)
        refreshSearchText(savedBookId)
        deleteOrphans()
        savedBookId
    }

    /**
     * Schrijft opgezochte gegevens naar boek en werk. Vergrendelde (zelf ingevulde) velden blijven
     * altijd staan; andere velden alleen als ze leeg zijn, of ook gevuld bij [refresh].
     * [status] null laat de verrijkingsstatus ongemoeid (bij importeren in een bestaand boek).
     */
    suspend fun applyMetadata(bookId: Long, metadata: BookMetadata, refresh: Boolean, status: EnrichStatus?) =
        db.withTransaction {
            val book = dao.getBook(bookId) ?: return@withTransaction
            val locked = book.lockedFields.toFieldSet()
            // Geïmporteerde auteurs en vertalers zijn rommelig (rollen omgedraaid); de eerste
            // bron die ze kent, vervangt ze.
            val provisional = book.importRunId != null && book.enrichStatus == EnrichStatus.NEW && status != null
            fun <T> pick(field: String, current: T?, found: T?): T? = when {
                field in locked || found == null -> current
                refresh || current == null || (current is String && current.isBlank()) -> found
                else -> current
            }

            dao.updateBook(
                book.copy(
                    title = pick("title", book.title, metadata.title) ?: book.title,
                    subtitle = pick("subtitle", book.subtitle, metadata.subtitle),
                    language = pick("language", book.language, metadata.language),
                    translators = metadata.translators.joinToString(", ").ifEmpty { null }.let { found ->
                        if (provisional && found != null && "translators" !in locked) found else pick("translators", book.translators, found)
                    },
                    publisher = pick("publisher", book.publisher, metadata.publisher),
                    publishedYear = pick("publishedYear", book.publishedYear, metadata.publishedYear),
                    pages = pick("pages", book.pages, metadata.pages),
                    format = pick("format", book.format, metadata.format),
                    description = pick("description", book.description, metadata.description),
                    coverUrl = pick("cover", book.coverUrl, metadata.coverUrl),
                    openLibraryEditionId = metadata.openLibraryEditionId ?: book.openLibraryEditionId,
                    enrichStatus = when {
                        status == null -> book.enrichStatus
                        book.enrichStatus == EnrichStatus.ENRICHED && status == EnrichStatus.QUICK -> book.enrichStatus
                        else -> status
                    },
                    enrichedSources = (book.enrichedSources.toFieldSet(", ") + metadata.source.split(", ").filter(String::isNotBlank))
                        .joinToString(", ").ifEmpty { null },
                    updatedAt = Instant.now(),
                ),
            )

            var work = dao.getPrimaryWork(bookId)
            if (work == null) {
                work = Work(title = "").let { it.copy(id = dao.insertWork(it)) }
                dao.insertBookWork(BookWork(bookId, work.id, position = 0))
            }
            val currentAuthors = dao.getAuthorNames(work.id)
            if ("authors" !in locked && metadata.authors.isNotEmpty() && (refresh || provisional || currentAuthors.isEmpty())) {
                setWorkAuthors(work.id, metadata.authors)
            }
            val seriesLocked = "series" in locked
            val takeSeries = !seriesLocked && metadata.seriesName != null && (refresh || work.seriesId == null)
            val series = if (takeSeries) findOrCreateSeries(metadata.seriesName!!) else null
            // Zelfde serie, maar nog zonder deelnummer: dat nummer alsnog overnemen.
            val sameSeries = !seriesLocked && work.seriesId != null &&
                work.seriesId.let { dao.getSeries(it)?.name }.equals(metadata.seriesName, ignoreCase = true)
            val originalTitle = pick("originalTitle", work.originalTitle, metadata.originalTitle)
            dao.updateWork(
                work.copy(
                    title = originalTitle ?: work.title.ifBlank { metadata.title.orEmpty() },
                    originalTitle = originalTitle,
                    originalLanguage = pick("originalLanguage", work.originalLanguage, metadata.originalLanguage),
                    firstPublishedYear = pick("firstPublishedYear", work.firstPublishedYear, metadata.firstPublishedYear),
                    seriesId = series?.id ?: work.seriesId,
                    seriesIndex = when {
                        takeSeries -> metadata.seriesIndex
                        sameSeries -> work.seriesIndex ?: metadata.seriesIndex
                        else -> work.seriesIndex
                    },
                    openLibraryWorkId = metadata.openLibraryWorkId ?: work.openLibraryWorkId,
                    wikidataId = metadata.workWikidataId ?: work.wikidataId,
                    updatedAt = Instant.now(),
                ),
            )
            // Zelf losgekoppeld: niet automatisch weer aan andere edities hangen.
            if (WORK_LOCK !in locked) linkToExistingWork(bookId, work.id, metadata)
            refreshSearchText(bookId)
            deleteOrphans()
        }

    /**
     * Als een ander boek al een werk heeft met hetzelfde Wikidata- of Open Library-id,
     * is dit een andere editie of vertaling ervan: koppel het boek aan dat werk.
     */
    private suspend fun linkToExistingWork(bookId: Long, workId: Long, metadata: BookMetadata) {
        val existing = metadata.workWikidataId?.let { dao.findWorkByWikidataId(it, workId) }
            ?: metadata.openLibraryWorkId?.let { dao.findWorkByOpenLibraryId(it, workId) }
            ?: return
        if (dao.countBooksForWork(workId) != 1) return // Werk wordt gedeeld; niet automatisch samenvoegen.
        val own = dao.getPrimaryWork(bookId) ?: return
        dao.deleteBookWork(bookId, workId)
        dao.insertBookWork(BookWork(bookId, existing.id, position = 0))
        if (existing.seriesId == null && own.seriesId != null) {
            dao.updateWork(existing.copy(seriesId = own.seriesId, seriesIndex = own.seriesIndex, updatedAt = Instant.now()))
        }
        if (dao.getAuthorNames(existing.id).isEmpty()) setWorkAuthors(existing.id, dao.getAuthorNames(workId))
    }

    fun observePrimaryWorks(): Flow<List<BookWorkRow>> = dao.observePrimaryWorks()

    /**
     * Twee boeken zijn edities van hetzelfde werk: het werk van [bookId] gaat op in dat van [otherBookId].
     * De gegevens van dat werk blijven; lege velden worden aangevuld uit het andere.
     */
    suspend fun linkAsSameWork(bookId: Long, otherBookId: Long) = db.withTransaction {
        val from = dao.getPrimaryWork(bookId) ?: return@withTransaction
        val into = dao.getPrimaryWork(otherBookId) ?: return@withTransaction
        if (from.id == into.id) return@withTransaction
        val affected = dao.bookIdsOfWork(from.id) + dao.bookIdsOfWork(into.id)
        dao.updateWork(
            into.copy(
                originalTitle = into.originalTitle ?: from.originalTitle,
                originalLanguage = into.originalLanguage ?: from.originalLanguage,
                firstPublishedYear = listOfNotNull(into.firstPublishedYear, from.firstPublishedYear).minOrNull(),
                seriesId = into.seriesId ?: from.seriesId,
                seriesIndex = if (into.seriesId != null) into.seriesIndex else from.seriesIndex,
                awards = into.awards ?: from.awards,
                openLibraryWorkId = into.openLibraryWorkId ?: from.openLibraryWorkId,
                wikidataId = into.wikidataId ?: from.wikidataId,
                updatedAt = Instant.now(),
            ),
        )
        if (dao.getAuthorNames(into.id).isEmpty()) setWorkAuthors(into.id, dao.getAuthorNames(from.id))
        dao.moveBookWorks(from.id, into.id)
        dao.deleteBookWorksOf(from.id)
        // Samengevoegd is een bewuste keuze: een eerdere "losgekoppeld"-markering vervalt.
        affected.distinct().forEach { id ->
            dao.getBook(id)?.let { book -> setLock(book, WORK_LOCK, false) }
            refreshSearchText(id)
        }
        deleteOrphans()
    }

    /**
     * Dit boek hoort niet bij de andere edities (een verkeerde koppeling): het krijgt weer een eigen werk,
     * met dezelfde auteurs en serie, en wordt niet meer automatisch gekoppeld.
     */
    suspend fun splitFromWork(bookId: Long) = db.withTransaction {
        val book = dao.getBook(bookId) ?: return@withTransaction
        val work = dao.getPrimaryWork(bookId) ?: return@withTransaction
        if (dao.countBooksForWork(work.id) < 2) return@withTransaction
        val newWorkId = dao.insertWork(
            Work(
                title = book.title,
                originalTitle = work.originalTitle,
                originalLanguage = work.originalLanguage,
                firstPublishedYear = work.firstPublishedYear,
                seriesId = work.seriesId,
                seriesIndex = work.seriesIndex,
            ),
        )
        setWorkAuthors(newWorkId, dao.getAuthorNames(work.id))
        dao.replaceBookWork(bookId, work.id, newWorkId)
        setLock(book, WORK_LOCK, true)
        refreshSearchText(bookId)
    }

    private suspend fun setLock(book: Book, field: String, locked: Boolean) {
        val current = book.lockedFields.toFieldSet()
        val updated = if (locked) current + field else current - field
        if (updated != current) dao.updateBook(book.copy(lockedFields = updated.sorted().joinToString(",").ifEmpty { null }, updatedAt = Instant.now()))
    }

    suspend fun delete(bookId: Long) {
        val book = db.withTransaction {
            val book = dao.getBook(bookId) ?: return@withTransaction null
            dao.deleteBook(book)
            deleteOrphans()
            book
        }
        covers.delete(book?.coverFile)
    }

    /** Eigen cover-foto instellen (of met null weghalen); de vorige foto wordt opgeruimd. */
    suspend fun setCoverFile(bookId: Long, coverFile: String?) {
        val previous = dao.getBook(bookId)?.coverFile
        dao.setCoverFile(bookId, coverFile, Instant.now())
        if (previous != coverFile) covers.delete(previous)
    }

    suspend fun getBook(bookId: Long): Book? = dao.getBook(bookId)

    /** Een cover-foto die toch bij geen enkel boek terechtkwam. */
    fun discardCoverFile(name: String) = covers.discard(name)

    suspend fun setFavorite(bookId: Long, favorite: Boolean) = dao.setFavorite(bookId, favorite, Instant.now())

    suspend fun setOwner(bookId: Long, owner: String?) {
        dao.setOwner(bookId, owner?.trim()?.ifEmpty { null }, Instant.now())
        refreshSearchText(bookId)
    }

    fun observeOwnerNames(): Flow<List<String>> = dao.observeOwnerNames()

    // --- Meerdere boeken tegelijk (meervoudige selectie) ---

    suspend fun setOwner(ids: Collection<Long>, owner: String?) = db.withTransaction {
        dao.setOwnerFor(ids.toList(), owner?.trim()?.ifEmpty { null }, Instant.now())
        ids.forEach { refreshSearchText(it) }
    }

    suspend fun setFavorite(ids: Collection<Long>, favorite: Boolean) = dao.setFavoriteFor(ids.toList(), favorite, Instant.now())

    suspend fun setWantToRead(ids: Collection<Long>, wantToRead: Boolean) = dao.setWantToReadFor(ids.toList(), wantToRead, Instant.now())

    /** Tags toevoegen; bestaande tags van elk boek blijven staan. */
    suspend fun addTags(ids: Collection<Long>, tags: List<String>) = db.withTransaction {
        val clean = tags.map(String::trim).filter(String::isNotEmpty)
        if (clean.isEmpty()) return@withTransaction
        ids.forEach { id ->
            val book = dao.getBook(id) ?: return@forEach
            val merged = (book.tags?.split(',').orEmpty().map(String::trim) + clean)
                .filter(String::isNotEmpty).distinctBy(String::lowercase).joinToString(", ")
            if (merged != book.tags) dao.updateBook(book.copy(tags = merged, updatedAt = Instant.now()))
            refreshSearchText(id)
        }
    }

    suspend fun delete(ids: Collection<Long>) = ids.forEach { delete(it) }

    private suspend fun findOrCreateSeries(name: String): Series? {
        val trimmed = name.trim().ifEmpty { return null }
        return dao.findSeries(trimmed) ?: Series(name = trimmed).let { it.copy(id = dao.insertSeries(it)) }
    }

    private suspend fun setWorkAuthors(workId: Long, names: List<String>) {
        dao.clearWorkAuthors(workId)
        dao.insertWorkAuthors(
            names.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase).mapIndexed { index, name ->
                val authorId = dao.findAuthor(name)?.id ?: dao.insertAuthor(Author(name = name))
                WorkAuthor(workId, authorId, index)
            },
        )
    }

    /** Zoektekst opnieuw opbouwen uit boek, werk, auteurs en serie. */
    internal suspend fun refreshSearchText(bookId: Long) {
        val book = dao.getBook(bookId) ?: return
        val work = dao.getPrimaryWork(bookId)
        val authors = work?.let { dao.getAuthorNames(it.id) }.orEmpty()
        val series = work?.seriesId?.let { dao.getSeries(it)?.name }
        val text = TextNormalizer.searchKey(
            listOf(book.title, book.subtitle, work?.originalTitle, series, book.isbn13, book.publisher, book.tags, book.translators, book.owner) + authors,
        )
        if (text != book.searchText) dao.updateBook(book.copy(searchText = text))
    }

    private suspend fun deleteOrphans() {
        dao.deleteOrphanWorks()
        dao.deleteOrphanAuthors()
        dao.deleteOrphanSeries()
    }

    private fun String?.toFieldSet(separator: String = ","): Set<String> =
        this?.split(separator)?.map(String::trim)?.filter(String::isNotEmpty)?.toSet().orEmpty()
}
