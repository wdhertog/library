package nl.wdhertog.library.lookup.sources

import nl.wdhertog.library.lookup.BookMetadata
import nl.wdhertog.library.lookup.LookupHttp
import nl.wdhertog.library.util.LanguageCodes

/** Deutsche Nationalbibliothek (SRU, MARC21, metadata CC0). */
class DnbSource(private val http: LookupHttp) : BookSource {
    override val name = "DNB"

    override suspend fun lookup(isbn13: String): BookMetadata? =
        http.get("https://services.dnb.de/sru/dnb?version=1.1&operation=searchRetrieve&query=num%3D$isbn13&recordSchema=MARC21-xml")
            ?.let(::parse)

    companion object {
        fun parse(xml: String): BookMetadata? {
            val record = parseFirstMarcRecord(xml) ?: return null
            val title = record.field("245")
            // 700-velden met $t verwijzen naar een werk (bijv. het origineel), niet naar een persoon.
            val contributors = record.fields("700").filter { it['t'] == null }
            fun MarcField.hasRole(vararg roles: String) = all('e').any { e -> roles.any { e.startsWith(it) } }
            fun List<MarcField>.names() = mapNotNull { it['a']?.let(::invertName) }.distinct()
            val authors = (record.fields("100") + contributors.filter { it.hasRole("Verfasser", "Autor") }).names()
            val translators = contributors.filter { it.hasRole("Übersetzer") }.names()
            val series = (record.fields("800") + record.fields("830") + record.fields("490"))
                .firstOrNull { it['a'] != null && !isPublisherSeriesNumber(it['v']) }
            val language = record.field("041")

            return BookMetadata(
                source = "DNB",
                title = title?.get('a')?.isbdTrim(),
                subtitle = title?.get('b')?.isbdTrim(),
                authors = authors,
                translators = translators,
                language = LanguageCodes.toIso1(language?.get('a')),
                originalLanguage = LanguageCodes.toIso1(language?.get('h')),
                originalTitle = record.field("240")?.get('a')?.isbdTrim(),
                publisher = (record.field("264") ?: record.field("260"))?.get('b')?.isbdTrim(),
                publishedYear = yearIn((record.field("264") ?: record.field("260"))?.get('c')),
                pages = record.field("300")?.get('a')?.let { Regex("""(\d+)\s*S""").find(it)?.groupValues?.get(1)?.toIntOrNull() }.plausiblePages(),
                seriesName = series?.get('a')?.isbdTrim(),
                seriesIndex = series?.get('v')?.isbdTrim(),
            ).takeIf { it.hasContent }
        }
    }
}
