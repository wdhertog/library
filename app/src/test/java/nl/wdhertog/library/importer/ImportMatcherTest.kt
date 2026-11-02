package nl.wdhertog.library.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ImportMatcherTest {

    private val existing = listOf(
        ExistingBook(1, "Eragon", listOf("Christopher Paolini"), "9789022546208", hirtId = null),
        ExistingBook(2, "De avonden: een winterverhaal", listOf("Gerard Reve"), null, hirtId = null),
        ExistingBook(3, "Oudste", listOf("Christopher Paolini"), "9789022546215", hirtId = 77),
        ExistingBook(4, "Stoner", listOf("John Williams"), "9789025368913", hirtId = null),
    )

    @Test
    fun `matches on hirt id, isbn and title plus author`() {
        val matches = ImportMatcher.match(
            listOf(
                ImportedBook("Oudste", hirtId = 77),
                ImportedBook("Eragon", isbn13 = "9789022546208"),
                ImportedBook("Avonden", authors = listOf("G. Reve"), isbn13 = "9789023417163"),
            ),
            existing,
        )
        assertEquals(
            listOf(3L to MatchReason.HIRT_ID, 1L to MatchReason.ISBN, 2L to MatchReason.TITLE_AUTHOR),
            matches.map { it!!.existing.id to it.reason },
        )
    }

    @Test
    fun `different isbns are only a possible match`() {
        val match = ImportMatcher.match(listOf(ImportedBook("Stoner", authors = listOf("John Williams"), isbn13 = "9780099561330")), existing).single()
        assertEquals(4L, match?.existing?.id)
        assertEquals(MatchReason.OTHER_ISBN, match?.reason)
        assertFalse(match!!.certain)
    }

    @Test
    fun `an existing book is matched only once`() {
        val matches = ImportMatcher.match(
            listOf(ImportedBook("Eragon", isbn13 = "9789022546208"), ImportedBook("Eragon", isbn13 = "9789022546208")),
            existing,
        )
        assertEquals(1L, matches[0]?.existing?.id)
        assertNull(matches[1])
    }
}
