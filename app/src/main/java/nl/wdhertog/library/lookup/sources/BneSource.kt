package nl.wdhertog.library.lookup.sources

import nl.wdhertog.library.lookup.BookMetadata
import nl.wdhertog.library.lookup.LookupHttp
import nl.wdhertog.library.util.LanguageCodes
import nl.wdhertog.library.util.TextNormalizer

/** Biblioteca Nacional de España (SRU van de Alma-catalogus, MARC21, geen sleutel). */
class BneSource(private val http: LookupHttp) : BookSource {
    override val name = "BNE"

    override suspend fun lookup(isbn13: String): BookMetadata? =
        http.get(
            "https://catalogo.bne.es/view/sru/34BNE_INST?version=1.2&operation=searchRetrieve" +
                "&query=alma.isbn=$isbn13&maximumRecords=1",
        )?.let(::parse)

    companion object {
        private const val ROLE_AUTHOR = "aut"
        private const val ROLE_TRANSLATOR = "trl"

        fun parse(xml: String): BookMetadata? {
            val record = parseFirstMarcRecord(xml) ?: return null
            val title = record.field("245")
            // 700-velden met $t verwijzen naar een werk, niet naar een persoon.
            val contributors = record.fields("700").filter { it['t'] == null }
            fun MarcField.hasRole(code: String, word: String) = all('4').contains(code) || all('e').any { it.startsWith(word) }
            fun List<MarcField>.names() = mapNotNull { it['a']?.isbdTrim()?.let(::invertName) }.distinct()
            val authors = (record.fields("100") + contributors.filter { it.hasRole(ROLE_AUTHOR, "autor") }).names()
            val translators = contributors.filter { it.hasRole(ROLE_TRANSLATOR, "trad") }.names()
            val publication = record.field("264") ?: record.field("260")
            val publisher = publication?.get('b')?.isbdTrim()
            val language = record.field("041")

            return BookMetadata(
                source = "BNE",
                title = title?.get('a')?.isbdTrim(),
                subtitle = title?.get('b')?.isbdTrim(),
                authors = authors,
                translators = translators,
                language = LanguageCodes.toIso1(language?.get('a') ?: record.languageFrom008()),
                originalLanguage = LanguageCodes.toIso1(language?.get('h')),
                originalTitle = record.field("240")?.get('a')?.isbdTrim()?.takeIf { language?.get('h') != null },
                publisher = publisher,
                publishedYear = yearIn(publication?.get('c')),
                pages = record.field("300")?.get('a')?.let { Regex("""(\d+)\s*p""").find(it)?.groupValues?.get(1)?.toIntOrNull() }.plausiblePages(),
                seriesName = series(record, publisher)?.first,
                seriesIndex = series(record, publisher)?.second,
            ).takeIf { it.hasContent }
        }

        /**
         * Een serie met deelnummer. Uitgeversreeksen ("Letras de bolsillo ; 82") dragen meestal de
         * naam van de uitgever en tellen niet mee; de catalogus zet echte series vaak tussen haken.
         */
        private fun series(record: List<MarcField>, publisher: String?): Pair<String, String>? {
            val publisherKey = publisher?.let(TextNormalizer::normalize)
            return (record.fields("800") + record.fields("830") + record.fields("490"))
                .mapNotNull { field ->
                    val name = (field['t'] ?: field['a'])?.isbdTrim()?.removeSurrounding("[", "]")?.trim() ?: return@mapNotNull null
                    val index = field['v']?.isbdTrim() ?: return@mapNotNull null
                    name to index
                }
                .firstOrNull { (name, index) ->
                    !isPublisherSeriesNumber(index) && (publisherKey == null || TextNormalizer.normalize(name) != publisherKey)
                }
        }

        /** Taalcode op positie 35–37 van veld 008, als 041 ontbreekt. */
        private fun List<MarcField>.languageFrom008(): String? =
            field("008")?.get(CONTROL)?.takeIf { it.length >= 38 }?.substring(35, 38)?.takeIf { it.all(Char::isLetter) }
    }
}
