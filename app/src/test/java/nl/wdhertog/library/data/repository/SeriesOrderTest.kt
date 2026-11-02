package nl.wdhertog.library.data.repository

import nl.wdhertog.library.data.db.Ownership
import nl.wdhertog.library.data.db.SeriesBookRow
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesOrderTest {

    private fun row(workId: Long, index: String?, bookId: Long = workId, language: String = "nl", read: Boolean = false) =
        SeriesBookRow(workId, "Werk $workId", index, "Auteur", bookId, "Boek $bookId", language, Ownership.OWNED, null, null, read)

    @Test
    fun `years are not volume numbers`() {
        val volumes = SeriesOrder.volumes(listOf(row(1, "2022")), totalVolumes = null)
        assertEquals(1, volumes.size)
    }

    @Test
    fun `sort keys`() {
        assertEquals(2.0, SeriesOrder.sortKey("2"), 0.0)
        assertEquals(0.5, SeriesOrder.sortKey("0,5"), 0.0)
        assertEquals(4.0, SeriesOrder.sortKey("IV"), 0.0)
        assertEquals(3.0, SeriesOrder.sortKey("3a"), 0.0)
        assertEquals(Double.MAX_VALUE, SeriesOrder.sortKey(null), 0.0)
    }

    @Test
    fun `volumes in order with editions grouped and gaps`() {
        val volumes = SeriesOrder.volumes(
            listOf(row(3, "3"), row(1, "1", bookId = 10, language = "en", read = true), row(1, "1", bookId = 11), row(5, null), row(2, "0.5")),
            totalVolumes = 4,
        )
        assertEquals(listOf("0.5", "1", "2", "3", "4", null), volumes.map { it.index })
        val first = volumes[1] as SeriesVolume.Present
        assertEquals(listOf(10L, 11L), first.books.map { it.bookId })
        assertEquals(true, first.read)
        assertEquals(listOf("2", "4"), volumes.filterIsInstance<SeriesVolume.Missing>().map { it.index })
    }
}
