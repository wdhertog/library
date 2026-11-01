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
import nl.wdhertog.library.scan.Isbn
import nl.wdhertog.library.util.TextNormalizer
import java.time.Instant

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

    /** Slaat een handmatig ingevoerd of bewerkt boek op en geeft het id terug. bookId 0 = nieuw. */
    suspend fun save(bookId: Long, draft: BookDraft): Long = db.withTransaction {
        val now = Instant.now()
        val existing = if (bookId != 0L) dao.getBook(bookId) else null
        val previousDraft = existing?.let { loadDraft(it.id) }
        val locked = existing?.lockedFields.toFieldSet() + draft.changedFields(previousDraft)

        val isbn13 = Isbn.toIsbn13(draft.isbn)
        val authors = draft.cleanAuthors
        val seriesName = draft.seriesName.trim()
        val series = seriesName.takeIf(String::isNotEmpty)?.let { name ->
            dao.findSeries(name) ?: Series(name = name).let { it.copy(id = dao.insertSeries(it)) }
        }

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
            searchText = TextNormalizer.searchKey(
                listOf(draft.title, draft.subtitle, draft.originalTitle, seriesName, isbn13, draft.publisher, draft.tags) + authors,
            ),
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
                    title = originalTitle ?: work.title,
                    originalTitle = originalTitle,
                    seriesId = series?.id,
                    seriesIndex = draft.seriesIndex.trim().ifEmpty { null },
                    updatedAt = now,
                ),
            )
            work.id
        }

        dao.clearWorkAuthors(workId)
        dao.insertWorkAuthors(
            authors.mapIndexed { index, name ->
                val authorId = dao.findAuthor(name)?.id ?: dao.insertAuthor(Author(name = name))
                WorkAuthor(workId, authorId, index)
            },
        )
        dao.deleteOrphanAuthors()
        dao.deleteOrphanSeries()
        savedBookId
    }

    suspend fun delete(bookId: Long) = db.withTransaction {
        val book = dao.getBook(bookId) ?: return@withTransaction
        dao.deleteBook(book)
        dao.deleteOrphanWorks()
        dao.deleteOrphanAuthors()
        dao.deleteOrphanSeries()
    }

    private fun String?.toFieldSet(): Set<String> =
        this?.split(',')?.filter(String::isNotEmpty)?.toSet().orEmpty()
}
