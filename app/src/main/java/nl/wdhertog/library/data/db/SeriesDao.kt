package nl.wdhertog.library.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

data class SeriesSummary(
    val id: Long,
    val name: String,
    val totalVolumes: Int?,
    /** Delen (werken) waarvan je minstens één exemplaar hebt. */
    val works: Int,
    /** Delen waarvan je een exemplaar in bezit hebt (niet geleend of weggedaan). */
    val owned: Int,
    /** Delen die je (in welke editie dan ook) hebt uitgelezen. */
    val read: Int,
    /** Hoogste genummerde deel, als het nummer een geheel getal is. */
    val maxIndex: Int?,
)

/** Eén exemplaar van een deel in de serie. */
data class SeriesBookRow(
    val workId: Long,
    val workTitle: String,
    val seriesIndex: String?,
    val authors: String?,
    val bookId: Long,
    val bookTitle: String,
    val language: String?,
    val ownership: Ownership,
    val coverUrl: String?,
    val coverFile: String?,
    val read: Boolean,
)

@Dao
interface SeriesDao {

    @Query(
        """
        SELECT s.id, s.name,
               -- Zonder eigen opgave: het laatste deel volgens Wikidata, als dat minstens de helft van de nummers kent
               -- (zelfde regel als SeriesOrder.completeTotal).
               COALESCE(s.totalVolumes, (
                   SELECT CASE WHEN COUNT(DISTINCT n) * 2 >= MAX(n) THEN MAX(n) END FROM (
                       SELECT CAST(p.ordinal AS INTEGER) AS n FROM series_part p
                       WHERE p.seriesId = s.id AND p.ordinal GLOB '[0-9]*' AND p.ordinal NOT GLOB '*[^0-9]*'
                         AND CAST(p.ordinal AS INTEGER) BETWEEN 1 AND 100
                   )
               )) AS totalVolumes,
               COUNT(DISTINCT w.id) AS works,
               COUNT(DISTINCT CASE WHEN b.ownership = 'OWNED' THEN w.id END) AS owned,
               COUNT(DISTINCT CASE WHEN rs.status = 'FINISHED' THEN w.id END) AS read,
               MAX(CASE WHEN w.seriesIndex GLOB '[0-9]*' AND w.seriesIndex NOT GLOB '*[^0-9]*'
                        THEN CAST(w.seriesIndex AS INTEGER) END) AS maxIndex
        FROM series s
        JOIN work w ON w.seriesId = s.id
        JOIN book_work bw ON bw.workId = w.id
        JOIN book b ON b.id = bw.bookId
        LEFT JOIN reading_session rs ON rs.bookId IN (SELECT bookId FROM book_work WHERE workId = w.id)
        GROUP BY s.id
        ORDER BY s.name COLLATE NOCASE
        """
    )
    fun observeSummaries(): Flow<List<SeriesSummary>>

    @Query("SELECT * FROM series WHERE id = :id")
    fun observeSeries(id: Long): Flow<Series?>

    @Query(
        """
        SELECT w.id AS workId, w.title AS workTitle, w.seriesIndex,
               (SELECT GROUP_CONCAT(a.name, ', ') FROM work_author wa JOIN author a ON a.id = wa.authorId
                 WHERE wa.workId = w.id) AS authors,
               b.id AS bookId, b.title AS bookTitle, b.language, b.ownership, b.coverUrl, b.coverFile,
               EXISTS (SELECT 1 FROM reading_session rs JOIN book_work x ON x.bookId = rs.bookId
                        WHERE x.workId = w.id AND rs.status = 'FINISHED') AS read
        FROM work w
        JOIN book_work bw ON bw.workId = w.id
        JOIN book b ON b.id = bw.bookId
        WHERE w.seriesId = :seriesId
        ORDER BY b.language, b.title
        """
    )
    fun observeBooks(seriesId: Long): Flow<List<SeriesBookRow>>

    @Query("SELECT * FROM series WHERE name = :name COLLATE NOCASE AND id != :excludeId LIMIT 1")
    suspend fun findOther(name: String, excludeId: Long): Series?

    @Query("SELECT * FROM series WHERE id = :id")
    suspend fun get(id: Long): Series?

    @Update
    suspend fun update(series: Series)

    /** Samenvoegen: alle delen van [fromId] naar [intoId]. */
    @Query("UPDATE work SET seriesId = :intoId, updatedAt = :now WHERE seriesId = :fromId")
    suspend fun moveWorks(fromId: Long, intoId: Long, now: java.time.Instant)

    @Query("DELETE FROM series WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE work SET seriesIndex = :index, updatedAt = :now WHERE id = :workId")
    suspend fun setIndex(workId: Long, index: String?, now: java.time.Instant)

    @Query("UPDATE work SET seriesId = NULL, seriesIndex = NULL, updatedAt = :now WHERE id = :workId")
    suspend fun removeFromSeries(workId: Long, now: java.time.Instant)

    @Query("SELECT bookId FROM book_work WHERE workId IN (SELECT id FROM work WHERE seriesId = :seriesId)")
    suspend fun bookIds(seriesId: Long): List<Long>

    @Query("SELECT bookId FROM book_work WHERE workId = :workId")
    suspend fun bookIdsOfWork(workId: Long): List<Long>

    // --- Delen volgens Wikidata ---

    @Query("SELECT * FROM series_part WHERE seriesId = :seriesId")
    fun observeParts(seriesId: Long): Flow<List<SeriesPart>>

    @Query("DELETE FROM series_part WHERE seriesId = :seriesId")
    suspend fun clearParts(seriesId: Long)

    @androidx.room.Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertParts(parts: List<SeriesPart>)

    @Query("SELECT DISTINCT wikidataId FROM work WHERE seriesId = :seriesId AND wikidataId IS NOT NULL")
    suspend fun workWikidataIds(seriesId: Long): List<String>

    @Query("UPDATE series SET partsFetchedAt = :at WHERE id = :id")
    suspend fun setPartsFetched(id: Long, at: java.time.Instant)

    @Query("SELECT id FROM series WHERE partsFetchedAt IS NULL OR partsFetchedAt < :before ORDER BY name COLLATE NOCASE")
    suspend fun seriesNeedingParts(before: java.time.Instant): List<Long>
}
