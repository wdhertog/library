package nl.wdhertog.library.lookup.sources

import nl.wdhertog.library.lookup.BookMetadata
import nl.wdhertog.library.lookup.LookupHttp
import nl.wdhertog.library.scan.Isbn
import nl.wdhertog.library.util.LanguageCodes

/** Bibliothèque nationale de France (SRU, UNIMARC). */
class BnfSource(private val http: LookupHttp) : BookSource {
    override val name = "BnF"

    override suspend fun lookup(isbn13: String): BookMetadata? {
        // Oudere records zijn soms alleen op ISBN-10 vindbaar.
        for (isbn in listOfNotNull(isbn13, Isbn.toIsbn10(isbn13))) {
            val xml = http.get(
                "https://catalogue.bnf.fr/api/SRU?version=1.2&operation=searchRetrieve" +
                    "&query=bib.isbn%20all%20%22$isbn%22&recordSchema=unimarcXchange&maximumRecords=1",
            ) ?: continue
            parse(xml)?.let { return it }
        }
        return null
    }

    companion object {
        private const val ROLE_AUTHOR = "070"
        private const val ROLE_TRANSLATOR = "730"

        fun parse(xml: String): BookMetadata? {
            val record = parseFirstMarcRecord(xml) ?: return null
            val title = record.field("200")
            fun name(field: MarcField) = listOfNotNull(field['b'], field['a']).joinToString(" ").trim()
            val people = record.fields("700") + record.fields("701") + record.fields("702")
            val authors = people.filter { it.tag != "702" && (it['4'] == null || it['4'] == ROLE_AUTHOR) }.map(::name)
            val translators = people.filter { it['4'] == ROLE_TRANSLATOR }.map(::name)
            val publication = record.field("214") ?: record.field("210")
            val series = record.field("461")?.takeUnless { isPublisherSeriesNumber(it['v']) }
            val language = record.field("101")

            return BookMetadata(
                source = "BnF",
                title = title?.get('a')?.isbdTrim(),
                subtitle = title?.all('e')?.firstOrNull { !it.startsWith("[") }?.isbdTrim(),
                authors = authors.filter(String::isNotEmpty).distinct(),
                translators = translators.filter(String::isNotEmpty).distinct(),
                language = LanguageCodes.toIso1(language?.get('a')),
                originalLanguage = LanguageCodes.toIso1(language?.get('c')),
                originalTitle = record.field("454")?.get('t')?.isbdTrim(),
                publisher = publication?.get('c')?.isbdTrim(),
                publishedYear = yearIn(publication?.get('d')),
                pages = record.field("215")?.get('a')?.let { Regex("""(\d+)\s*p""").find(it)?.groupValues?.get(1)?.toIntOrNull() }.plausiblePages(),
                seriesName = series?.get('t')?.isbdTrim(),
                seriesIndex = series?.get('v')?.isbdTrim(),
            ).takeIf { it.hasContent }
        }
    }
}
