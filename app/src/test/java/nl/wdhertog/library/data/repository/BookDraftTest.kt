package nl.wdhertog.library.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class BookDraftTest {

    @Test
    fun `nieuw boek vergrendelt alleen ingevulde velden`() {
        val draft = BookDraft(title = "Eragon", authors = listOf("Christopher Paolini", ""), seriesName = "Het Erfgoed")
        assertEquals(setOf("title", "authors", "series"), draft.changedFields(previous = null))
    }

    @Test
    fun `bewerken vergrendelt alleen gewijzigde velden`() {
        val before = BookDraft(title = "Eragon", authors = listOf("Christopher Paolini"), publisher = "Gottmer")
        val after = before.copy(publisher = "Van Goor", notes = "signed")
        assertEquals(setOf("publisher"), after.changedFields(before))
    }

    @Test
    fun `auteurs zonder dubbelen en lege regels`() {
        val draft = BookDraft(authors = listOf(" Jason Matthews ", "", "jason matthews", "Graham Robb"))
        assertEquals(listOf("Jason Matthews", "Graham Robb"), draft.cleanAuthors)
    }
}
