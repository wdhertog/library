package nl.wdhertog.library.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.lookup.sources.OpenLibrarySource
import nl.wdhertog.library.lookup.sources.WikidataSource
import nl.wdhertog.library.util.Genres
import java.time.Instant

/**
 * Genres op het werk (Work.genres, ontwerp §12): alleen de vaste lijst van [Genres]. Opgehaald bij
 * Wikidata (P136 van het werk), anders de echte genres uit de onderwerpen bij Open Library. Elk werk één
 * keer (genresFetchedAt): een genre dat je daarna weghaalt, komt niet terug.
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

    /** Eén ronde van hooguit [batch] werken. Geeft het aantal verwerkte werken; null = bron nu niet bereikbaar. */
    suspend fun runBatch(batch: Int = 40): Int? {
        val targets = dao.worksWithoutGenres(batch)
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
        for (target in targets) {
            val genres = fromWikidata[target.wikidataId].orEmpty().mapNotNull(Genres::genreOf).ifEmpty {
                val work = target.openLibraryWorkId?.takeIf { openLibraryEnabled() } ?: return@ifEmpty emptyList()
                val subjects = try {
                    openLibrary.workSubjects(work) ?: return null
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    emptyList()
                }
                subjects.mapNotNull(Genres::fromOpenLibrary)
            }
            addGenres(target.workId, genres, fetched = true)
        }
        return targets.size
    }

    /**
     * Genres terughalen uit de tags van een oude back-up (van vóór 1.10, toen genres nog tags waren): bij
     * elk boek dat hier nog bestaat, de genres uit die tags bij zijn werk erbij. Wat het werk al heeft, blijft.
     * Geeft het aantal werken dat genres kreeg.
     */
    suspend fun restoreFromTags(tagsByBookUuid: Map<String, String?>): Int {
        val byUuid = dao.getBooks().associateBy { it.uuid }
        val perWork = mutableMapOf<Long, MutableSet<String>>()
        for ((uuid, tags) in tagsByBookUuid) {
            val book = byUuid[uuid] ?: continue
            val genres = Genres.split(tags?.split(',').orEmpty()).first
            if (genres.isEmpty()) continue
            val work = dao.getPrimaryWork(book.id) ?: continue
            perWork.getOrPut(work.id) { mutableSetOf() } += genres
        }
        var changed = 0
        for ((workId, genres) in perWork) {
            val before = dao.getWork(workId)?.genres
            addGenres(workId, genres.toList(), fetched = false)
            if (dao.getWork(workId)?.genres != before) changed++
        }
        return changed
    }

    /** Zelf gekozen genres voor een werk; telt ook als opgehaald, zodat er niets bij komt wat je weghaalde. */
    suspend fun setGenres(workId: Long, genres: Collection<String>) = db.withTransaction {
        val work = dao.getWork(workId) ?: return@withTransaction
        dao.updateWork(work.copy(genres = Genres.encode(genres), genresFetchedAt = work.genresFetchedAt ?: Instant.now(), updatedAt = Instant.now()))
        dao.bookIdsOfWork(workId).forEach { books.refreshSearchText(it) }
    }

    private suspend fun addGenres(workId: Long, genres: List<String>, fetched: Boolean) = db.withTransaction {
        val work = dao.getWork(workId) ?: return@withTransaction
        val merged = Genres.encode(Genres.decode(work.genres) + genres)
        val fetchedAt = if (fetched) Instant.now() else work.genresFetchedAt
        if (merged == work.genres && fetchedAt == work.genresFetchedAt) return@withTransaction
        dao.updateWork(work.copy(genres = merged, genresFetchedAt = fetchedAt, updatedAt = Instant.now()))
        if (merged != work.genres) dao.bookIdsOfWork(workId).forEach { books.refreshSearchText(it) }
    }

    /**
     * Eenmalig: genres uit je tags naar het genreveld van het werk. Synoniemen ("krimi", "sci-fi") verdwijnen
     * uit de tags; subgenres ("high fantasy") en eigen tags blijven. Geeft het aantal gewijzigde boeken.
     */
    suspend fun moveGenresFromTags(): Int = db.withTransaction {
        var changed = 0
        for (book in dao.getBooks()) {
            val tags = book.tags?.split(',').orEmpty().map(String::trim).filter(String::isNotEmpty)
            if (tags.isEmpty()) continue
            val (genres, remaining) = Genres.split(tags)
            if (genres.isEmpty() && remaining == tags) continue
            dao.getPrimaryWork(book.id)?.let { addGenres(it.id, genres, fetched = false) }
            if (remaining != tags) dao.updateBook(book.copy(tags = remaining.joinToString(", ").ifEmpty { null }, updatedAt = Instant.now()))
            books.refreshSearchText(book.id)
            changed++
        }
        changed
    }
}
