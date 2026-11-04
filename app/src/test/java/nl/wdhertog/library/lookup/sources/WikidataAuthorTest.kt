package nl.wdhertog.library.lookup.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Auteur uit Wikidata, op een echt antwoord (Jussi Adler-Olsen, 4 november 2026). */
class WikidataAuthorTest {
    private fun resource(name: String) = requireNotNull(javaClass.getResource("/lookup/$name")).readText()

    @Test
    fun `author found by name, Dutch description preferred`() {
        val author = WikidataSource.parseAuthor(resource("wikidata_author_adler_olsen.json"), "Jussi Adler-Olsen")!!
        assertEquals("Q930301", author.id)
        assertEquals(1950, author.birthYear)
        assertNull(author.deathYear)
        assertTrue(author.description!!.startsWith("Deens"))
        assertEquals("Koninkrijk Denemarken", author.country)
    }

    @Test
    fun `inverted name works too, another name does not`() {
        val json = resource("wikidata_author_adler_olsen.json")
        assertEquals("Q930301", WikidataSource.parseAuthor(json, "Adler-Olsen, Jussi")?.id)
        assertNull(WikidataSource.parseAuthor(json, "Stephen King"))
    }

    @Test
    fun `query from the author or from the works`() {
        assertTrue(WikidataSource.buildAuthorQuery("Q930301", emptyList())!!.contains("VALUES ?author { wd:Q930301 }"))
        assertTrue(WikidataSource.buildAuthorQuery(null, listOf("Q1348207"))!!.contains("?w wdt:P50 ?author"))
        assertNull(WikidataSource.buildAuthorQuery(null, emptyList()))
    }
}
