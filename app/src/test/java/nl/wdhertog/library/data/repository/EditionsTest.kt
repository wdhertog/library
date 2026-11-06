package nl.wdhertog.library.data.repository

import nl.wdhertog.library.data.db.Ownership
import nl.wdhertog.library.data.db.SeriesBookRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EditionsTest {

    private fun facts(uuid: String, owned: Boolean = true, cover: Boolean = true, language: String? = "en", added: Long = 0) =
        EditionFacts(uuid, owned, cover, language, added)

    @Test
    fun `chosen edition first, otherwise owned, cover, Dutch, oldest`() {
        val list = listOf(
            facts("en-old", added = 1),
            facts("nl-new", language = "nl", added = 9),
            facts("nl-nocover", language = "nl", cover = false),
            facts("gone", owned = false, language = "nl"),
        )
        assertEquals("nl-new", Editions.primaryOf(list, null) { it }?.uuid)
        assertEquals("gone", Editions.primaryOf(list, "gone") { it }?.uuid)
        assertTrue(Editions.isChosen(list, "gone") { it })
        // Een keuze die niet (meer) bij deze edities hoort, telt niet.
        assertEquals("nl-new", Editions.primaryOf(list, "elders") { it }?.uuid)
        assertFalse(Editions.isChosen(list, "elders") { it })
    }

    @Test
    fun `series shows the chosen edition's title and cover`() {
        fun row(book: Long, uuid: String, title: String, language: String, primary: String?) = SeriesBookRow(
            workId = 1, workTitle = "The Lord of the Rings", seriesIndex = "1", authors = "Tolkien", bookId = book,
            bookTitle = title, language = language, ownership = Ownership.OWNED, coverUrl = "https://x/$book.jpg", coverFile = null,
            read = false, bookUuid = uuid, addedAt = Instant.ofEpochMilli(book), primaryBookUuid = primary,
        )
        val automatic = SeriesOrder.volumes(listOf(row(1, "a", "The Lord of the Rings", "en", null), row(2, "b", "In de ban van de ring", "nl", null)), null)
        val auto = automatic.single() as SeriesVolume.Present
        assertEquals(2L, auto.books.first().bookId) // Nederlands eerst: die cover
        assertEquals("The Lord of the Rings", auto.title) // niet gekozen: titel van het werk
        val chosen = SeriesOrder.volumes(listOf(row(1, "a", "The Lord of the Rings", "en", "b"), row(2, "b", "In de ban van de ring", "nl", "b")), null)
        assertEquals("In de ban van de ring", (chosen.single() as SeriesVolume.Present).title)
    }
}
