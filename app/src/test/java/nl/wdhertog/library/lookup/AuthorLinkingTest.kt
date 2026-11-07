package nl.wdhertog.library.lookup

import nl.wdhertog.library.lookup.sources.OpenLibrarySource
import nl.wdhertog.library.lookup.sources.WikidataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthorLinkingTest {
    @Test
    fun `name variants turn surname-first names`() {
        assertEquals(listOf("Tolkien, J.R.R.", "J.R.R. Tolkien"), WikidataSource.nameVariants("Tolkien, J.R.R."))
        assertEquals(listOf("Dick Laan"), WikidataSource.nameVariants(" Dick Laan "))
    }

    @Test
    fun `people from a search, duplicates once`() {
        val json = """{"results":{"bindings":[
            {"person":{"value":"http://www.wikidata.org/entity/Q1209714"}},
            {"person":{"value":"http://www.wikidata.org/entity/Q1209714"}},
            {"person":{"value":"http://www.wikidata.org/entity/Q5"}}]}}"""
        assertEquals(listOf("Q1209714", "Q5"), WikidataSource.parsePeople(json))
    }

    @Test
    fun `open library author with wikidata id`() {
        assertEquals("Stephen R. Covey" to "Q313482", OpenLibrarySource.parseAuthorRecord("""{"name":"Stephen R. Covey","remote_ids":{"wikidata":"Q313482","viaf":"1"}}"""))
        assertEquals("Iemand" to null, OpenLibrarySource.parseAuthorRecord("""{"name":"Iemand"}"""))
        assertNull(OpenLibrarySource.parseAuthorRecord("""{"key":"/authors/OL1A"}"""))
    }

    @Test
    fun `a known id is trusted even when the label differs`() {
        val json = """{"results":{"bindings":[{"author":{"value":"http://www.wikidata.org/entity/Q6691"},"label":{"value":"Ὅμηρος"},"birth":{"value":"-0800-01-01T00:00:00Z"}}]}}"""
        assertNull(WikidataSource.parseAuthor(json, "Homerus"))
        assertEquals("Q6691", WikidataSource.parseAuthor(json, "Homerus", trustId = true)?.id)
    }
}
