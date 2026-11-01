package nl.wdhertog.library.lookup

import nl.wdhertog.library.lookup.sources.BookSource
import nl.wdhertog.library.lookup.sources.WikidataWork
import nl.wdhertog.library.util.LanguageCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataMergerTest {

    @Test
    fun `eerste bron wint per veld, lege velden worden aangevuld`() {
        val kb = BookMetadata(source = "KB", title = "Oudste", authors = listOf("Christopher Paolini"), seriesName = "Het erfgoed")
        val ol = BookMetadata(source = "Open Library", title = "Eldest", coverUrl = "https://covers/x.jpg", pages = 600)
        val merged = MetadataMerger.merge(listOf(kb, ol))!!
        assertEquals("Oudste", merged.title)
        assertEquals("https://covers/x.jpg", merged.coverUrl)
        assertEquals(600, merged.pages)
        assertEquals("Het erfgoed", merged.seriesName)
        assertEquals("KB, Open Library", merged.source)
    }

    @Test
    fun `serie met volgnummer gaat voor serie zonder`() {
        val a = BookMetadata(source = "A", seriesName = "Erfgoed")
        val b = BookMetadata(source = "B", seriesName = "Het Erfgoed", seriesIndex = "2")
        val merged = MetadataMerger.merge(listOf(a, b))!!
        assertEquals("Het Erfgoed", merged.seriesName)
        assertEquals("2", merged.seriesIndex)
    }

    @Test
    fun `Wikidata-serie vervangt die van de catalogus`() {
        val merged = BookMetadata(source = "KB", seriesName = "Het erfgoed")
        val result = MetadataMerger.withWikidata(merged, WikidataWork("Q308982", "Het Erfgoed", "2", "Q2699024"))
        assertEquals("Het Erfgoed", result.seriesName)
        assertEquals("2", result.seriesIndex)
        assertEquals("Q308982", result.workWikidataId)
    }

    @Test
    fun `Wikidata zonder serie houdt de catalogusserie`() {
        val merged = BookMetadata(source = "KB", seriesName = "Serie Q")
        val result = MetadataMerger.withWikidata(merged, WikidataWork("Q1", null, null, null))
        assertEquals("Serie Q", result.seriesName)
        assertEquals("Q1", result.workWikidataId)
    }

    @Test
    fun `niets gevonden`() {
        assertNull(MetadataMerger.merge(emptyList()))
    }

    @Test
    fun `router kiest bron op ISBN-prefix`() {
        fun source(name: String) = object : BookSource {
            override val name = name
            override suspend fun lookup(isbn13: String): BookMetadata? = null
        }
        val router = SourceRouter(source("KB"), source("OL"), source("DNB"), source("BnF"))
        assertEquals(listOf("KB", "OL"), router.sourcesFor("9789023495635").map { it.name })
        assertEquals(listOf("KB", "OL"), router.sourcesFor("9789400514133").map { it.name })
        assertEquals(listOf("DNB", "OL"), router.sourcesFor("9783499246128").map { it.name })
        assertEquals(listOf("BnF", "OL"), router.sourcesFor("9782081285880").map { it.name })
        assertEquals(listOf("OL"), router.sourcesFor("9781786076403").map { it.name })
    }

    @Test
    fun `taalcodes uit catalogi`() {
        assertEquals("nl", LanguageCodes.toIso1("dut"))
        assertEquals("nl", LanguageCodes.toIso1("nld"))
        assertEquals("de", LanguageCodes.toIso1("ger"))
        assertEquals("en", LanguageCodes.toIso1("/languages/eng"))
        assertEquals("fr", LanguageCodes.toIso1("fr"))
        assertNull(LanguageCodes.toIso1("xx-yy"))
    }
}
