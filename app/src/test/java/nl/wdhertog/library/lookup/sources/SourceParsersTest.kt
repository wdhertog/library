package nl.wdhertog.library.lookup.sources

import nl.wdhertog.library.data.db.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Parsers getest tegen echte antwoorden van de bronnen (src/test/resources/lookup). */
class SourceParsersTest {

    private fun resource(name: String): String =
        requireNotNull(javaClass.getResource("/lookup/$name")) { "ontbreekt: $name" }.readText()

    @Test
    fun `KB - vertaald boek met oorspronkelijke titel en vertalers`() {
        val book = KbSource.parse(resource("kb_9789023495635.json"))!!
        assertEquals("Dictator", book.title)
        assertEquals(listOf("Robert Harris"), book.authors) // naam zoals op het boek, niet "Robert Dennis Harris"
        assertEquals(listOf("Q313981"), book.authorWikidataIds)
        assertEquals(listOf("Arjaan van Nimwegen", "Thijs van Nimwegen"), book.translators.sorted()) // illustrator Gower niet
        assertEquals("nl", book.language)
        assertEquals("Dictator", book.originalTitle)
        assertEquals("en", book.originalLanguage)
        assertEquals("Cargo", book.publisher)
        assertEquals(2015, book.publishedYear)
        assertEquals(380, book.pages)
    }

    @Test
    fun `KB - serie en alleen personen als auteur`() {
        val book = KbSource.parse(resource("kb_9789049202118.json"))!!
        assertEquals("Murtagh", book.title)
        assertEquals(listOf("Christopher Paolini"), book.authors) // vertaalbureau TextCase niet
        assertEquals("Het erfgoed", book.seriesName)
        assertEquals("Boekerij", book.publisher)
    }

    @Test
    fun `KB - bij herdrukken het nieuwste record`() {
        val book = KbSource.parse(resource("kb_9789022561713.json"))!!
        assertEquals("Eragon", book.title)
        assertEquals(2023, book.publishedYear)
        assertEquals(listOf("Erica Feberwee"), book.translators)
    }

    @Test
    fun `KB - naam zoals op het boek, zonder titel en co-auteur`() {
        val book = KbSource.parse(resource("kb_9789400510555.json"))!!
        assertEquals("De keuze", book.title)
        assertEquals(listOf("Edith Eva Eger"), book.authors) // KB: "Edita Eva Elefánt Eger"; label: "Dr. Edith Eva Eger met …"
        assertEquals(listOf("Elisabeth van Borselen"), book.translators)
    }

    @Test
    fun `KB - oorspronkelijke titel zonder ondertitel`() {
        val book = KbSource.parse(resource("kb_9789051945539.json"))!!
        assertEquals("Liturgy of the ordinary", book.originalTitle)
    }

    @Test
    fun `KB - onbekend ISBN`() {
        assertNull(KbSource.parse("""{"head":{"vars":[]},"results":{"bindings":[]}}"""))
    }

    @Test
    fun `Open Library - data en editie`() {
        val data = OpenLibrarySource.parseData(resource("ol_data_9781786076403.json"), "9781786076403")!!
        assertEquals("Breakfast with Einstein", data.title)
        assertEquals(listOf("Chad Orzel"), data.authors)
        assertEquals(2019, data.publishedYear)
        assertEquals(288, data.pages)
        assertEquals("OL31856945M", data.openLibraryEditionId)
        assert(data.coverUrl!!.startsWith("https://covers.openlibrary.org/"))

        val edition = OpenLibrarySource.parseEdition(resource("ol_edition_9781786076403.json"))
        assertEquals("OL20570719W", edition.openLibraryWorkId)
        assertEquals(BookFormat.PAPERBACK, edition.format)
    }

    @Test
    fun `Open Library - serie met nummer uit editie`() {
        val edition = OpenLibrarySource.parseEdition("""{"series": ["Discworld #5"], "languages": [{"key": "/languages/eng"}]}""")
        assertEquals("Discworld", edition.seriesName)
        assertEquals("5", edition.seriesIndex)
        assertEquals("en", edition.language)
    }

    @Test
    fun `DNB - MARC21 met uitgeversreeks die geen serie is`() {
        val book = DnbSource.parse(resource("dnb_9783499246128.xml"))!!
        assertEquals("Veritas", book.title)
        assertEquals("Roman", book.subtitle)
        assertEquals(listOf("Rita Monaldi", "Francesco Sorti"), book.authors)
        assertEquals("de", book.language)
        assertEquals("it", book.originalLanguage)
        assertEquals("Rowohlt-Taschenbuch-Verl.", book.publisher)
        assertEquals(2008, book.publishedYear)
        assertEquals(958, book.pages)
        assertNull(book.seriesName) // "Rororo 24612" is een uitgeversreeks
    }

    @Test
    fun `BNE - taal uit veld 008, uitgeversreeks zonder nummer telt niet`() {
        val book = BneSource.parse(resource("bne_sombra.xml"))!!
        assertEquals("La sombra del viento", book.title)
        assertEquals(listOf("Carlos Ruiz Zafón"), book.authors)
        assertEquals("es", book.language)
        assertEquals("Booket", book.publisher)
        assertEquals(2016, book.publishedYear)
        assertEquals(575, book.pages)
        assertNull(book.seriesName)
        assertNull(book.originalTitle) // 240 is hier de eigen titel, geen vertaling
    }

    @Test
    fun `BNE - vertaling, serie tussen haken, uitgeversreeks met de naam van de uitgever niet`() {
        val book = BneSource.parse(resource("bne_harry_potter.xml"))!!
        assertEquals("Harry Potter y la piedra filosofal", book.title)
        assertEquals(listOf("J. K. Rowling"), book.authors)
        assertEquals(listOf("Alicia Dellepiane Rawson"), book.translators)
        assertEquals("es", book.language)
        assertEquals("en", book.originalLanguage)
        assertEquals("Harry Potter and the philosopher's stone", book.originalTitle)
        assertEquals("Letras de Bolsillo", book.publisher)
        assertEquals("Harry Potter", book.seriesName)
        assertEquals("1", book.seriesIndex)
    }

    @Test
    fun `SBN - volledig record met vertalers`() {
        val book = SbnSource.parse(resource("sbn_full_translated.json"))!!
        assertEquals("Harry Potter e la pietra filosofale", book.title)
        assertEquals(listOf("J. K. Rowling"), book.authors)
        assertEquals(listOf("Stefano Bartezzaghi", "Marina Astrologo"), book.translators)
        assertEquals("it", book.language)
        assertEquals("Salani", book.publisher)
        assertEquals(2020, book.publishedYear)
        assertEquals(310, book.pages)
    }

    @Test
    fun `SBN - zoekresultaat en sorteertekens in de titel`() {
        assertEquals("IT\\ICCU\\TO0\\1968404", SbnSource.recordId("""{"numFound":1,"briefRecords":[{"codiceIdentificativo":"IT\\ICCU\\TO0\\1968404"}]}"""))
        assertNull(SbnSource.recordId("""{"numFound":0,"briefRecords":[]}"""))
        val book = SbnSource.parse(
            """{"titolo":"\u0088Il \u0089signore degli anelli : trilogia / John Ronald Reuel Tolkien","nomi":["[Autore]  Tolkien, J. R. R.","[Traduttore]  Levi, Primo <1919-1987>"],"pubblicazione":"Milano : Bompiani, [2014]","descrizioneFisica":"1253 p., [48] c. di tav.","linguaPubblicazione":"ITALIANO"}""",
        )!!
        assertEquals("Il signore degli anelli", book.title)
        assertEquals("trilogia", book.subtitle)
        assertEquals(listOf("J. R. R. Tolkien"), book.authors)
        assertEquals(listOf("Primo Levi"), book.translators) // levensjaren weg
        assertEquals("Bompiani", book.publisher)
        assertEquals(2014, book.publishedYear)
        assertEquals(1253, book.pages)
    }

    @Test
    fun `BnF - UNIMARC`() {
        val book = BnfSource.parse(resource("bnf_9782081285880.xml"))!!
        assertEquals("Cyrano de Bergerac", book.title)
        assertEquals(listOf("Edmond Rostand"), book.authors)
        assertEquals("fr", book.language)
        assertEquals("Flammarion", book.publisher)
        assertEquals(2012, book.publishedYear)
        assertEquals(262, book.pages)
        assertNull(book.seriesName) // collectie "Étonnants classiques" is geen serie
    }

    @Test
    fun `Wikidata - serie in de taal van het boek met volgnummer`() {
        val work = WikidataSource.parse(resource("wikidata_oudste.json"), preferredLanguage = "nl")!!
        assertEquals("Q308982", work.workId)
        assertEquals("Het Erfgoed", work.seriesName)
        assertEquals("2", work.seriesIndex)

        val german = WikidataSource.parse(resource("wikidata_oudste.json"), preferredLanguage = "de")!!
        assertEquals("Eragon-Tetralogie", german.seriesName)
    }

    @Test
    fun `Wikidata - vroegste publicatiedatum is de eerste uitgave`() {
        fun row(date: String) =
            """{"work":{"type":"uri","value":"http://www.wikidata.org/entity/Q74287"},"published":{"type":"literal","value":"$date"}}"""
        val json = """{"head":{"vars":[]},"results":{"bindings":[${row("1938-03-01T00:00:00Z")},${row("1937-09-21T00:00:00Z")}]}}"""
        val work = WikidataSource.parse(json, preferredLanguage = "en")!!
        assertEquals(1937, work.firstPublishedYear)
        assertNull(work.seriesName)
    }

    @Test
    fun `Wikidata - query met auteur-id of naam, escapet aanhalingstekens`() {
        val byId = WikidataSource.buildQuery(listOf("Oudste", "Eldest"), listOf("Q93620"), emptyList())!!
        assert("wd:Q93620" in byId)
        assert("\"oudste\" \"eldest\"" in byId)

        val byName = WikidataSource.buildQuery(listOf("Say \"hi\""), emptyList(), listOf("John le Carré"))!!
        assert("\"John le Carré\"@en" in byName)
        assert("\"say \\\"hi\\\"\"" in byName)

        assertNull(WikidataSource.buildQuery(listOf("Titel"), emptyList(), emptyList()))
    }
}
