package nl.wdhertog.library.lookup.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nl.wdhertog.library.lookup.BookMetadata
import nl.wdhertog.library.lookup.LookupHttp
import nl.wdhertog.library.util.Languages
import java.net.URLEncoder

/**
 * Servizio Bibliotecario Nazionale (Italiaanse bibliotheken samen, ICCU) via de open JSON-dienst
 * van de OPAC: eerst zoeken op ISBN, dan het volledige record.
 */
class SbnSource(private val http: LookupHttp) : BookSource {
    override val name = "SBN"

    override suspend fun lookup(isbn13: String): BookMetadata? {
        val search = http.get("$BASE/search.json?isbn=$isbn13") ?: return null
        val id = recordId(search) ?: return null
        return http.get("$BASE/full.json?bid=${URLEncoder.encode(id, "UTF-8")}")?.let(::parse)
    }

    companion object {
        private const val BASE = "https://opac.sbn.it/opacmobilegw"

        /** Onzichtbare tekens die in SBN-titels het lidwoord markeren dat bij sorteren niet meetelt. */
        private val sortMarkers = Regex("[\\u0088\\u0089\\u0098\\u009C]")
        private val role = Regex("""^\[([^\]]+)]\s*(.+)$""")

        /** Levensjaren achter een naam: "Levi, Primo <1919-1987>". */
        private val lifeDates = Regex("""\s*<[^>]*>""")

        private fun personName(raw: String) = invertName(raw.replace(lifeDates, "").trim())

        internal fun recordId(json: String): String? =
            ((Json.parseToJsonElement(json) as? JsonObject)?.get("briefRecords") as? JsonArray)
                ?.firstOrNull()?.let { (it as? JsonObject)?.str("codiceIdentificativo") }

        fun parse(json: String): BookMetadata? {
            val record = Json.parseToJsonElement(json) as? JsonObject ?: return null
            // "1: Harry Potter e la pietra filosofale / J. K. Rowling ; traduzione di …"
            val fullTitle = record.str("titolo")?.replace(sortMarkers, "") ?: return null
            val titlePart = fullTitle.substringBefore(" / ").replace(Regex("""^\d+\s*:\s*"""), "")
            val people = (record["nomi"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                .mapNotNull { entry -> role.matchEntire(entry.trim())?.let { it.groupValues[1] to personName(it.groupValues[2]) } }
            fun withRole(name: String) = people.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }.distinct()
            val authors = withRole("Autore").ifEmpty { listOfNotNull(record.str("autorePrincipale")?.let(::personName)) }
            // "Milano : Mondadori, 2019"
            val publication = record.str("pubblicazione")
            val publisher = publication?.substringAfter(" : ", "")?.substringBeforeLast(",")?.trim()?.ifEmpty { null }

            return BookMetadata(
                source = "SBN",
                title = titlePart.substringBefore(" : ").isbdTrim().ifEmpty { null },
                subtitle = titlePart.substringAfter(" : ", "").isbdTrim().ifEmpty { null },
                authors = authors,
                translators = withRole("Traduttore").filter { it !in authors },
                language = record.str("linguaPubblicazione")?.let { Languages.codeForName(it) },
                publisher = publisher,
                publishedYear = yearIn(publication),
                pages = record.str("descrizioneFisica")
                    ?.let { Regex("""(\d+)\s*p\.""").find(it)?.groupValues?.get(1)?.toIntOrNull() }.plausiblePages(),
            ).takeIf { it.hasContent }
        }

        private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
    }
}
