package nl.wdhertog.library.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import nl.wdhertog.library.data.db.Author
import nl.wdhertog.library.data.db.Book
import nl.wdhertog.library.data.db.BookWork
import nl.wdhertog.library.data.db.EnrichStatus
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.data.db.Series
import nl.wdhertog.library.data.db.Work
import nl.wdhertog.library.data.db.WorkAuthor
import nl.wdhertog.library.lookup.BookMetadata
import nl.wdhertog.library.scan.Isbn
import nl.wdhertog.library.util.TextNormalizer
import java.time.Instant

sealed interface ScanAddResult {
    val bookId: Long

    data class Added(override val bookId: Long) : ScanAddResult
    data class AlreadyPresent(override val bookId: Long) : ScanAddResult
}

class BookRepository(private val db: LibraryDatabase) {

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
                    borrowedFromPersonId = existing.borrowedFromPersonId,
                    borrowedDueDate = existing.borrowedDueDate,
                    locationId = existing.locationId,
                    shelfPosition = existing.shelfPosition,
                    enrichStatus = existing.enrichStatus,
                    enrichedSources = existing.enrichedSources,
                    openLibraryEditionId = existing.openLibraryEditionId,
                    storygraphId = existing.storygraphId,
                    hirtId = existing.hirtId,
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
     */
    suspend fun applyMetadata(bookId: Long, metadata: BookMetadata, refresh: Boolean, status: EnrichStatus) =
        db.withTransaction {
            val book = dao.getBook(bookId) ?: return@withTransaction
            val locked = book.lockedFields.toFieldSet()
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
                    translators = pick("translators", book.translators, metadata.translators.joinToString(", ").ifEmpty { null }),
                    publisher = pick("publisher", book.publisher, metadata.publisher),
                    publishedYear = pick("publishedYear", book.publishedYear, metadata.publishedYear),
                    pages = pick("pages", book.pages, metadata.pages),
                    format = pick("format", book.format, metadata.format),
                    description = pick("description", book.description, metadata.description),
                    coverUrl = pick("cover", book.coverUrl, metadata.coverUrl),
                    openLibraryEditionId = metadata.openLibraryEditionId ?: book.openLibraryEditionId,
                    enrichStatus = if (book.enrichStatus == EnrichStatus.ENRICHED && status == EnrichStatus.QUICK) book.enrichStatus else status,
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
            if ("authors" !in locked && metadata.authors.isNotEmpty() && (refresh || currentAuthors.isEmpty())) {
                setWorkAuthors(work.id, metadata.authors)
            }
            val seriesLocked = "series" in locked
            val takeSeries = !seriesLocked && metadata.seriesName != null && (refresh || work.seriesId == null)
            val series = if (takeSeries) findOrCreateSeries(metadata.seriesName!!) else null
            val originalTitle = pick("originalTitle", work.originalTitle, metadata.originalTitle)
            dao.updateWork(
                work.copy(
                    title = originalTitle ?: work.title.ifBlank { metadata.title.orEmpty() },
                    originalTitle = originalTitle,
                    originalLanguage = pick("originalLanguage", work.originalLanguage, metadata.originalLanguage),
                    firstPublishedYear = pick("firstPublishedYear", work.firstPublishedYear, metadata.firstPublishedYear),
                    seriesId = series?.id ?: work.seriesId,
                    seriesIndex = if (takeSeries) metadata.seriesIndex else work.seriesIndex,
                    openLibraryWorkId = metadata.openLibraryWorkId ?: work.openLibraryWorkId,
                    wikidataId = metadata.workWikidataId ?: work.wikidataId,
                    updatedAt = Instant.now(),
                ),
            )
            linkToExistingWork(bookId, work.id, metadata)
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

    suspend fun delete(bookId: Long) = db.withTransaction {
        val book = dao.getBook(bookId) ?: return@withTransaction
        dao.deleteBook(book)
        deleteOrphans()
    }

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
    private suspend fun refreshSearchText(bookId: Long) {
        val book = dao.getBook(bookId) ?: return
        val work = dao.getPrimaryWork(bookId)
        val authors = work?.let { dao.getAuthorNames(it.id) }.orEmpty()
        val series = work?.seriesId?.let { dao.getSeries(it)?.name }
        val text = TextNormalizer.searchKey(
            listOf(book.title, book.subtitle, work?.originalTitle, series, book.isbn13, book.publisher, book.tags, book.translators) + authors,
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
