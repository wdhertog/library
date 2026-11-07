package nl.wdhertog.library.lookup

import nl.wdhertog.library.lookup.sources.WikidataSource
import org.junit.Assert.assertEquals
import org.junit.Test

class WikidataGenresTest {
    private fun row(work: String, genre: String, label: String, lang: String) =
        """{"work":{"value":"http://www.wikidata.org/entity/$work"},"genre":{"value":"http://www.wikidata.org/entity/$genre"},"label":{"value":"$label"},"lang":{"value":"$lang"}}"""

    @Test
    fun `dutch label per genre, english when there is none`() {
        val json = """{"results":{"bindings":[${listOf(
            row("Q1", "Q10", "fantasy", "nl"), row("Q1", "Q10", "fantasy literature", "en"),
            row("Q1", "Q11", "high fantasy", "en"),
            row("Q2", "Q12", "krimi", "nl"),
        ).joinToString(",")}]}}"""
        val genres = WikidataSource.parseGenres(json)
        assertEquals(listOf("fantasy", "high fantasy"), genres["Q1"])
        assertEquals(listOf("krimi"), genres["Q2"])
    }
}
