package nl.wdhertog.library.lookup.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Delen van een serie uit Wikidata, op een echt antwoord (Department Q, 4 november 2026). */
class WikidataSeriesPartsTest {

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResource("/lookup/$name")) { "ontbreekt: $name" }.readText()

    @Test
    fun `all numbered parts with an English title when there is no Dutch one`() {
        val result = WikidataSource.parseSeriesParts(resource("wikidata_series_department_q.json"), listOf("Q1348207"), "Department Q")
        assertEquals("Q86567441", result.seriesId)
        assertEquals((1..9).map(Int::toString), result.parts.map { it.ordinal }.sortedBy(String::toInt))
        val first = result.parts.single { it.ordinal == "1" }
        assertEquals("The Keeper of Lost Causes", first.title)
        assertEquals(2007, first.year)
        assertNull(result.parts.single { it.ordinal == "3" }.year)
    }

    @Test
    fun `query starts from the series id or from the works`() {
        assertTrue(WikidataSource.buildSeriesPartsQuery("Q86567441", emptyList())!!.contains("VALUES ?series { wd:Q86567441 }"))
        assertTrue(WikidataSource.buildSeriesPartsQuery(null, listOf("Q1", "nep"))!!.contains("VALUES ?w { wd:Q1 }"))
        assertNull(WikidataSource.buildSeriesPartsQuery(null, listOf("nep")))
    }

    @Test
    fun `a work in two series picks the one with most own works, then the same name`() {
        fun row(series: String, label: String, part: String, ord: String) =
            """{"series":{"value":"http://www.wikidata.org/entity/$series"},"seriesLabel":{"value":"$label"},"part":{"value":"http://www.wikidata.org/entity/$part"},"ord":{"value":"$ord"},"label":{"value":"Deel $ord","xml:lang":"nl"},"lang":{"value":"nl"}}"""
        val json = """{"results":{"bindings":[
            ${row("Q10", "Schijfwereld", "Q1", "1")}, ${row("Q10", "Schijfwereld", "Q2", "2")}, ${row("Q10", "Schijfwereld", "Q3", "3")},
            ${row("Q20", "Nachtwacht", "Q2", "1")}, ${row("Q20", "Nachtwacht", "Q4", "2")}
        ]}}"""
        assertEquals("Q10", WikidataSource.parseSeriesParts(json, listOf("Q1", "Q2"), "Nachtwacht").seriesId)
        assertEquals("Q20", WikidataSource.parseSeriesParts(json, listOf("Q2"), "Nachtwacht").seriesId)
        assertEquals("Deel 2", WikidataSource.parseSeriesParts(json, listOf("Q2"), "Nachtwacht").parts.single { it.ordinal == "2" }.title)
    }
}
