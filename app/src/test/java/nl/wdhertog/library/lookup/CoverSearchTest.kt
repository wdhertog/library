package nl.wdhertog.library.lookup

import nl.wdhertog.library.lookup.search.BookSearch
import nl.wdhertog.library.lookup.search.CoverLine
import nl.wdhertog.library.lookup.search.CoverText
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverSearchTest {

    private fun resource(name: String) = javaClass.getResource("/lookup/$name")!!.readText()

    // Thrillercover: de auteur staat groter dan de titel.
    private val lines = listOf(
        CoverLine("GREGG", 120),
        CoverLine("HURWITZ", 130),
        CoverLine("AARTSVIJAND", 90),
        CoverLine("Orphan X", 40),
        CoverLine("DE NUMMER 1 BESTSELLER", 20),
    )

    @Test
    fun `search text and words from the biggest lines`() {
        val query = CoverText.searchText(lines)
        assertEquals("GREGG HURWITZ AARTSVIJAND", query)
        assertEquals(listOf("hurwitz", "gregg", "aartsvijand"), CoverText.searchWords(lines, query))
    }

    @Test
    fun `kb results are ranked with the author from the cover`() {
        val ranked = CoverText.rank(BookSearch.parseKb(resource("kb_search_aartsvijand.json")), "GREGG HURWITZ AARTSVIJAND")
        assertEquals("Aartsvijand", ranked.first().title)
        assertEquals(listOf("9789400518896", "9789044938463"), ranked.take(2).map { it.isbn13 })
        assertEquals(true, ranked.first().authors.first().contains("Hurwitz"))
        // Wilbur Smiths Aartsvijand komt daarna (alleen de titel klopt).
        assertEquals("Wilbur Addison Smith", ranked[2].authors.first())
    }

    @Test
    fun `open library results`() {
        val results = BookSearch.parseOpenLibrary(resource("ol_search_deep_water.json"))
        val first = results.first()
        assertEquals("Deep Water", first.title)
        assertEquals(listOf("Patricia Highsmith"), first.authors)
        assertEquals(1957, first.year)
        assertEquals(true, first.isbn13!!.startsWith("97"))
        assertEquals("https://covers.openlibrary.org/b/id/93441-M.jpg", first.coverUrl)
    }

    @Test
    fun `kb query needs the title word and one of the others, without quotes from the photo`() {
        val q = BookSearch.kbQuery("aartsvijand", listOf("hurwitz", "gre\"gg"))!!
        assertEquals(true, q.contains("CONTAINS(LCASE(STR(?name)), \"aartsvijand\")"))
        assertEquals(true, q.contains("CONTAINS(LCASE(STR(?an)), \"hurwitz\") || CONTAINS(LCASE(STR(?name)), \"gregg\")"))
        assertEquals(null, BookSearch.kbQuery("\"\"", emptyList()))
    }

    @Test
    fun `title and author for the form`() {
        assertEquals("Aartsvijand" to "Gregg Hurwitz", CoverText.guessTitleAndAuthor(lines + CoverLine("GREGG HURWITZ", 125)))
        // Vormgeving ("Ai", "A") telt niet; de naam bovenaan wordt de auteur.
        val ai = listOf(CoverLine("Ai", 300), CoverLine("A", 280), CoverLine("Dit", 150), CoverLine("ILYAZ NASRULLAH", 40), CoverLine("De valse belofte van", 30))
        assertEquals("Dit" to "Ilyaz Nasrullah", CoverText.guessTitleAndAuthor(ai))
    }

    @Test
    fun `keywords skip small and common words`() {
        assertEquals(listOf("diepe", "water"), CoverText.keywords("Het diepe water"))
        assertEquals(listOf("bourgondiërs"), CoverText.keywords("De Bourgondiërs"))
    }
}
