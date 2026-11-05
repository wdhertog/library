package nl.wdhertog.library.data.repository

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.data.db.Series
import nl.wdhertog.library.data.db.SeriesPart
import nl.wdhertog.library.data.db.SeriesSummary
import nl.wdhertog.library.lookup.sources.WikidataSource
import java.time.Duration
import java.time.Instant

data class SeriesDetail(val series: Series, val volumes: List<SeriesVolume>)

class SeriesRepository(
    private val db: LibraryDatabase,
    private val bookRepository: BookRepository,
    private val wikidata: WikidataSource? = null,
    /** Instellingen: Wikidata kan uit staan. */
    private val wikidataEnabled: () -> Boolean = { true },
) {
    private val dao = db.seriesDao()
    private val books = db.bookDao()

    fun observeSummaries(): Flow<List<SeriesSummary>> = dao.observeSummaries()

    fun observeDetail(seriesId: Long): Flow<SeriesDetail?> =
        combine(dao.observeSeries(seriesId), dao.observeBooks(seriesId), dao.observeParts(seriesId)) { series, rows, parts ->
            series?.let { SeriesDetail(it, SeriesOrder.volumes(rows, it.totalVolumes, parts, ManualParts.decode(it.manualParts))) }
        }

    /**
     * Een deel dat je nog niet hebt op je leeslijst zetten: een boek "niet in bezit" in deze serie, met de
     * auteur van de andere delen. Het staat dan in Lezen onder "Wil ik lezen" en telt niet als eigen boek.
     * Een zelf toegevoegd ontbrekend deel met hetzelfde nummer is daarna overbodig en verdwijnt.
     */
    suspend fun addToWishlist(seriesId: Long, ordinal: String, title: String): Long = db.withTransaction {
        val series = dao.get(seriesId) ?: error("Serie bestaat niet")
        val author = dao.mainAuthor(seriesId)
        val bookId = bookRepository.save(
            0,
            BookDraft(
                title = title.trim(),
                authors = listOf(author.orEmpty()),
                seriesName = series.name,
                seriesIndex = ordinal.trim(),
                wantToRead = true,
            ),
        )
        dao.markNotOwned(bookId, Instant.now())
        setManualPart(seriesId, ordinal, "")
        bookId
    }

    /**
     * Een ontbrekend deel zelf toevoegen of de titel aanpassen; een lege titel haalt het weg.
     * [previousOrdinal] bij het wijzigen van het nummer.
     */
    suspend fun setManualPart(seriesId: Long, ordinal: String, title: String, previousOrdinal: String? = null) = db.withTransaction {
        val series = dao.get(seriesId) ?: return@withTransaction
        val number = ordinal.trim()
        val keep = ManualParts.decode(series.manualParts).filter {
            SeriesOrder.sortKey(it.ordinal) != SeriesOrder.sortKey(number) &&
                (previousOrdinal == null || SeriesOrder.sortKey(it.ordinal) != SeriesOrder.sortKey(previousOrdinal))
        }
        val parts = if (title.isBlank() || number.isEmpty()) keep else keep + ManualPart(number, title.trim())
        dao.update(series.copy(manualParts = ManualParts.encode(parts), updatedAt = Instant.now()))
    }

    /**
     * De delen van een serie bij Wikidata ophalen (hooguit eens per [PARTS_MAX_AGE], tenzij [force]).
     * Geeft het aantal gevonden delen, of null als het nu niet lukte (geen verbinding).
     */
    suspend fun refreshParts(seriesId: Long, force: Boolean = false): Int? {
        val source = wikidata?.takeIf { wikidataEnabled() } ?: return null
        val series = dao.get(seriesId) ?: return null
        val fetched = series.partsFetchedAt
        if (!force && fetched != null && fetched.isAfter(Instant.now().minus(PARTS_MAX_AGE))) return null
        val result = try {
            source.seriesParts(series.wikidataId, dao.workWikidataIds(seriesId), series.name)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) { // ook een onverwacht antwoord (parsefout), niet alleen geen verbinding
            Log.i(TAG, "Delen van ${series.name} niet opgehaald: ${e.message}")
            null
        } ?: return null
        db.withTransaction {
            dao.clearParts(seriesId)
            dao.insertParts(result.parts.map { SeriesPart(seriesId, it.ordinal, it.title, it.year, it.wikidataId) })
            if (result.seriesId.isNotEmpty()) books.setSeriesWikidataIfMissing(seriesId, result.seriesId, Instant.now())
            dao.setPartsFetched(seriesId, Instant.now())
        }
        return result.parts.size
    }

    /**
     * Voor alle series waarvan de delen nog niet (of lang geleden) zijn opgehaald. Eén mislukking (bijv. een
     * time-out bij Wikidata) slaat die serie over; na drie op rij is er waarschijnlijk geen verbinding.
     */
    suspend fun refreshStaleParts() {
        var failures = 0
        for (id in dao.seriesNeedingParts(Instant.now().minus(PARTS_MAX_AGE))) {
            failures = if (refreshParts(id) == null) failures + 1 else 0
            if (failures >= 3) return
        }
    }

    /**
     * Naam wijzigen. Bestaat er al een serie met die naam (bijv. dezelfde serie uit een andere bron),
     * dan worden ze samengevoegd; geeft het id terug van de serie die overblijft.
     */
    suspend fun rename(seriesId: Long, name: String): Long = db.withTransaction {
        val affected = dao.bookIds(seriesId)
        val trimmed = name.trim()
        val series = dao.get(seriesId) ?: return@withTransaction seriesId
        if (trimmed.isEmpty()) return@withTransaction seriesId
        val other = dao.findOther(trimmed, seriesId)
        if (other == null) {
            dao.update(series.copy(name = trimmed, updatedAt = Instant.now()))
            affected.forEach { bookRepository.refreshSearchText(it) }
            seriesId
        } else {
            mergeInto(series, other)
            other.id
        }
    }

    /** Samenvoegen met een andere serie; [into] blijft over. */
    suspend fun merge(seriesId: Long, intoId: Long) = db.withTransaction {
        val series = dao.get(seriesId) ?: return@withTransaction
        val into = dao.get(intoId) ?: return@withTransaction
        mergeInto(series, into)
    }

    private suspend fun mergeInto(series: Series, into: Series) {
        val affected = dao.bookIds(series.id)
        dao.moveWorks(series.id, into.id, Instant.now())
        if (into.totalVolumes == null && series.totalVolumes != null) dao.update(into.copy(totalVolumes = series.totalVolumes))
        dao.delete(series.id)
        lockSeries(affected)
        affected.forEach { bookRepository.refreshSearchText(it) }
    }

    suspend fun setTotalVolumes(seriesId: Long, total: Int?) {
        val series = dao.get(seriesId) ?: return
        dao.update(series.copy(totalVolumes = total?.takeIf { it > 0 }, updatedAt = Instant.now()))
    }

    suspend fun setIndex(workId: Long, index: String?) = db.withTransaction {
        dao.setIndex(workId, index?.trim()?.ifEmpty { null }, Instant.now())
        lockSeries(dao.bookIdsOfWork(workId))
    }

    suspend fun removeFromSeries(workId: Long) = db.withTransaction {
        val affected = dao.bookIdsOfWork(workId)
        dao.removeFromSeries(workId, Instant.now())
        books.deleteOrphanSeries()
        lockSeries(affected)
        affected.forEach { bookRepository.refreshSearchText(it) }
    }

    /** Zelf aangepast: verrijking mag de serie van deze boeken niet meer overschrijven. */
    private suspend fun lockSeries(bookIds: List<Long>) {
        bookIds.forEach { id ->
            val book = books.getBook(id) ?: return@forEach
            val locked = book.lockedFields?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty().toSet()
            if ("series" !in locked) books.updateBook(book.copy(lockedFields = (locked + "series").sorted().joinToString(",")))
        }
    }

    companion object {
        private const val TAG = "SeriesRepository"

        /** Wikidata wordt aangevuld; eens per maand opnieuw kijken is genoeg. */
        val PARTS_MAX_AGE: Duration = Duration.ofDays(30)
    }
}
