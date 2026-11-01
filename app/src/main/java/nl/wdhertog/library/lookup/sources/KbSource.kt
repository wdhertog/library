package nl.wdhertog.library.lookup.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import nl.wdhertog.library.lookup.BookMetadata
import nl.wdhertog.library.lookup.LookupHttp
import nl.wdhertog.library.util.LanguageCodes

/**
 * Nederlandse Bibliografie van de KB via data.bibliotheken.nl (SPARQL, open data, geen sleutel).
 * Dekt het overgrote deel van de Nederlandstalige boeken, inclusief vertalers en oorspronkelijke titel.
 */
class KbSource(private val http: LookupHttp) : BookSource {
    override val name = "KB"

    override suspend fun lookup(isbn13: String): BookMetadata? {
        val body = http.postForm(
            ENDPOINT,
            mapOf("query" to QUERY.replace("\$ISBN", isbn13)),
            accept = "application/sparql-results+json",
        ) ?: return null
        return parse(body)
    }

    companion object {
        private const val ENDPOINT = "https://data.bibliotheken.nl/sparql"
        private const val SCHEMA = "http://schema.org/"
        private const val RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label"
        private const val ISSUED = "http://purl.org/dc/terms#issued"

        private val QUERY = """
            PREFIX schema: <http://schema.org/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            SELECT ?b ?p ?v ?name ?family ?label ?type ?lang ?sameAs WHERE {
              ?b schema:isbn "${'$'}ISBN" .
              {
                ?b ?p ?v .
                FILTER(?p IN (schema:name, rdfs:label, schema:inLanguage, schema:numberOfPages, <$ISSUED>))
              } UNION {
                ?b ?p ?v .
                FILTER(?p IN (schema:author, schema:contributor, schema:publication, schema:translationOfWork, schema:isPartOf))
                OPTIONAL { ?v schema:name ?name }
                OPTIONAL { ?v schema:familyName ?family }
                OPTIONAL { ?v rdfs:label ?label }
                OPTIONAL { ?v a ?type . FILTER(?type IN (schema:Person, schema:Organization, schema:BookSeries)) }
                OPTIONAL { ?v schema:inLanguage ?lang }
                OPTIONAL { ?v schema:sameAs ?sameAs . FILTER(STRSTARTS(STR(?sameAs), "http://www.wikidata.org/entity/")) }
              }
            }
        """.trimIndent()

        /** Boekenweekgeschenken en dergelijke staan als "serie" in de catalogus, maar zijn het niet. */
        private val ignoredSeries = Regex("(?i)^(geschenk|boekenweek)")

        private class Row(val record: String, val predicate: String, val value: String, val extra: Map<String, String>)

        private class Party(val name: String, val family: String?, val isPerson: Boolean, val wikidataId: String?)

        fun parse(json: String): BookMetadata? {
            val bindings = Json.parseToJsonElement(json).jsonObject["results"]?.jsonObject?.get("bindings")?.jsonArray
                ?: return null
            val rows = bindings.map { binding ->
                val values = (binding as JsonObject).mapValues { (_, v) -> v.str("value").orEmpty() }
                Row(values["b"].orEmpty(), values["p"].orEmpty(), values["v"].orEmpty(), values)
            }
            if (rows.isEmpty()) return null

            // Herdrukken hebben soms een eigen record met hetzelfde ISBN; neem het nieuwste.
            val record = rows.groupBy { it.record }.values.maxBy { recordRows ->
                recordRows.firstOrNull { it.predicate == ISSUED }?.value?.let(::yearIn) ?: 0
            }

            fun literal(predicate: String) = record.firstOrNull { it.predicate == predicate }?.value?.takeIf(String::isNotBlank)
            fun related(predicate: String) = record.filter { it.predicate == predicate }.groupBy { it.value }.values

            fun parties(predicate: String) = related(predicate).mapNotNull { rows ->
                val name = rows.firstNotNullOfOrNull { it.extra["name"] } ?: return@mapNotNull null
                val types = rows.mapNotNull { it.extra["type"] }
                Party(
                    name = name,
                    family = rows.firstNotNullOfOrNull { it.extra["family"] },
                    isPerson = types.isEmpty() || "${SCHEMA}Person" in types,
                    wikidataId = rows.firstNotNullOfOrNull { it.extra["sameAs"] }?.substringAfterLast('/'),
                )
            }

            val fullTitle = literal("${SCHEMA}name")
            val label = literal(RDFS_LABEL).orEmpty()
            val responsibility = label.substringAfter(" / ", "").substringBefore(" ; ")
            val translationPart = Regex("(?i)\\b(vert\\.|vertaald|vertaling)").find(label)
                ?.let { label.substring(it.range.first).substringBefore(" ; ") }.orEmpty()

            val authors = parties("${SCHEMA}author").filter { it.isPerson }
            val translators = parties("${SCHEMA}contributor").filter { party ->
                party.isPerson && party.family != null && translationPart.contains(party.family, ignoreCase = true)
            }

            val publication = related("${SCHEMA}publication").firstOrNull()?.firstNotNullOfOrNull { it.extra["label"] }
            val original = related("${SCHEMA}translationOfWork").firstOrNull()
            val series = related("${SCHEMA}isPartOf")
                .filter { rows -> rows.any { it.extra["type"] == "${SCHEMA}BookSeries" } }
                .firstNotNullOfOrNull { rows -> rows.firstNotNullOfOrNull { it.extra["name"] } }
                ?.takeUnless { ignoredSeries.containsMatchIn(it) }

            return BookMetadata(
                source = "KB",
                title = fullTitle?.substringBefore(" : ")?.trim(),
                subtitle = fullTitle?.substringAfter(" : ", "")?.trim()?.takeIf(String::isNotEmpty),
                authors = authors.map { displayName(it, responsibility) },
                authorWikidataIds = authors.mapNotNull { it.wikidataId },
                translators = translators.map { it.name },
                language = LanguageCodes.toIso1(literal("${SCHEMA}inLanguage")),
                originalTitle = original?.firstNotNullOfOrNull { it.extra["label"] }
                    ?.substringBefore(". - ")?.substringBefore(" : ")?.trim()?.trimEnd('.'),
                originalLanguage = LanguageCodes.toIso1(original?.firstNotNullOfOrNull { it.extra["lang"] }),
                publisher = publication?.let(::publisherFrom),
                publishedYear = yearIn(literal(ISSUED)),
                pages = literal("${SCHEMA}numberOfPages")?.toIntOrNull().plausiblePages(),
                seriesName = series,
            ).takeIf { it.hasContent }
        }

        /**
         * De KB gebruikt de volledige naam ("Robert Dennis Harris"); de verantwoordingsregel
         * bevat de naam zoals op het boek ("Robert Harris"). Gebruik die als hij te vinden is.
         */
        private fun displayName(party: Party, responsibility: String): String {
            val family = party.family ?: return party.name
            return responsibility.split(nameSeparators)
                .map { it.trim().replace(honorifics, "") }
                .firstOrNull { it.contains(family, ignoreCase = true) && it.length < 60 }
                ?: party.name
        }

        /** "Dr. Edith Eva Eger met Esmé Schwall Weigand" → twee namen. */
        private val nameSeparators = Regex(",|;| & | en | and | met | with | und | et ")
        private val honorifics = Regex("^(?i)(dr|prof|drs|ir|mr|ds)\\.\\s+")

        /** "Amsterdam : Cargo. 2015" → "Cargo". */
        private fun publisherFrom(label: String): String? =
            label.substringAfter(" : ", "").replace(Regex("[.,]?\\s*(\\[?\\d{4}\\]?)?\\s*$"), "").trim()
                .takeIf(String::isNotEmpty)
    }
}
