package nl.wdhertog.library.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.lookup.sources.OpenLibrarySource
import nl.wdhertog.library.lookup.sources.WikidataSource
import nl.wdhertog.library.util.Genres
import java.time.Instant

/**
 * Genres als tags (ontwerp §12, genres): van Wikidata (P136 van het werk), anders de bekende genres uit de
 * onderwerpen bij Open Library, gelijkgetrokken met [Genres]. Elk boek één keer: een genre dat je daarna
 * weghaalt, komt niet terug. Het boek krijgt daarvoor "Genres" in enrichedSources.
 */
class GenreService(
    private val db: LibraryDatabase,
    private val books: BookRepository,
    private val wikidata: WikidataSource,
    private val openLibrary: OpenLibrarySource,
    private val wikidataEnabled: () -> Boolean = { true },
    private val openLibraryEnabled: () -> Boolean = { true },
) {
    private val dao = db.bookDao()

    /** Eén ronde van hooguit [batch] boeken. Geeft het aantal verwerkte boeken; null = bron nu niet bereikbaar. */
    suspend fun runBatch(batch: Int = 40): Int? {
        val targets = dao.booksWithoutGenres(MARKER, batch)
        if (targets.isEmpty()) return 0
        val fromWikidata = if (wikidataEnabled()) {
            try {
                wikidata.genres(targets.mapNotNull { it.wikidataId }) ?: return null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }
        } else {
            emptyMap()
        }
        val subjects = mutableMapOf<String, List<String>>()
        for (target in targets) {
            val genres = fromWikidata[target.wikidataId].orEmpty().map(Genres::canonical).ifEmpty {
                val work = target.openLibraryWorkId?.takeIf { openLibraryEnabled() } ?: return@ifEmpty emptyList()
                val list = subjects.getOrPut(work) {
                    try {
                        openLibrary.workSubjects(work) ?: return null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
                list.mapNotNull(Genres::fromOpenLibrary)
            }
            apply(target.bookId, genres.distinct())
        }
        return targets.size
    }

    private suspend fun apply(bookId: Long, genres: List<String>) = db.withTransaction {
        val book = dao.getBook(bookId) ?: return@withTransaction
        val tags = book.tags?.split(',').orEmpty()
        val sources = (book.enrichedSources?.split(", ").orEmpty() + MARKER).filter(String::isNotBlank).distinct()
        dao.updateBook(
            book.copy(
                tags = Genres.normalizeTags(tags + genres).joinToString(", ").ifEmpty { null },
                enrichedSources = sources.joinToString(", "),
                updatedAt = Instant.now(),
            ),
        )
        books.refreshSearchText(bookId)
    }

    /** Eenmalig: varianten in bestaande tags gelijktrekken ("sci-fi" → "sciencefiction"). Geeft het aantal gewijzigde boeken. */
    suspend fun normalizeExistingTags(): Int = db.withTransaction {
        var changed = 0
        for (book in dao.getBooks()) {
            val tags = book.tags?.split(',').orEmpty().map(String::trim).filter(String::isNotEmpty)
            val normalized = Genres.normalizeTags(tags)
            if (normalized != tags) {
                dao.updateBook(book.copy(tags = normalized.joinToString(", ").ifEmpty { null }, updatedAt = Instant.now()))
                books.refreshSearchText(book.id)
                changed++
            }
        }
        changed
    }

    companion object {
        const val MARKER = "Genres"
    }
}
