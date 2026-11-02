package nl.wdhertog.library.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import nl.wdhertog.library.data.db.LibraryDatabase
import nl.wdhertog.library.data.db.Series
import nl.wdhertog.library.data.db.SeriesSummary
import java.time.Instant

data class SeriesDetail(val series: Series, val volumes: List<SeriesVolume>)

class SeriesRepository(private val db: LibraryDatabase, private val bookRepository: BookRepository) {
    private val dao = db.seriesDao()
    private val books = db.bookDao()

    fun observeSummaries(): Flow<List<SeriesSummary>> = dao.observeSummaries()

    fun observeDetail(seriesId: Long): Flow<SeriesDetail?> =
        combine(dao.observeSeries(seriesId), dao.observeBooks(seriesId)) { series, rows ->
            series?.let { SeriesDetail(it, SeriesOrder.volumes(rows, it.totalVolumes)) }
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
}
