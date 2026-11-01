package nl.wdhertog.library.lookup.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import nl.wdhertog.library.lookup.LookupHttp

data class WikidataWork(
    val workId: String,
    val seriesName: String?,
    val seriesIndex: String?,
    val seriesId: String?,
    /** Jaar van eerste publicatie van het werk (P577). */
    val firstPublishedYear: Int? = null,
)

/**
 * Zoekt het werk op Wikidata aan de hand van titel(s) en auteur, voor serie + volgnummer (P179/P1545)
 * en een werk-id waarmee vertalingen van hetzelfde boek gekoppeld kunnen worden.
 */
class WikidataSource(private val http: LookupHttp) {

    suspend fun findWork(
        titles: List<String>,
        authorIds: List<String>,
        authorNames: List<String>,
        preferredLanguage: String?,
    ): WikidataWork? {
        val query = buildQuery(titles, authorIds, authorNames) ?: return null
        val body = http.postForm(ENDPOINT, mapOf("query" to query), accept = "application/sparql-results+json") ?: return null
        return parse(body, preferredLanguage)
    }

    companion object {
        private const val ENDPOINT = "https://query.wikidata.org/sparql"
        private val idPattern = Regex("^Q\\d+$")

        private fun literal(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        fun buildQuery(titles: List<String>, authorIds: List<String>, authorNames: List<String>): String? {
            val titleValues = titles.map { it.trim().lowercase() }.filter(String::isNotEmpty).distinct()
            if (titleValues.isEmpty()) return null
            val ids = authorIds.filter { idPattern.matches(it) }.distinct()
            val authorClause = when {
                ids.isNotEmpty() -> "VALUES ?a { ${ids.joinToString(" ") { "wd:$it" }} }"
                authorNames.isNotEmpty() -> {
                    val names = authorNames.distinct().flatMap { name ->
                        listOf("en", "mul", "nl", "de", "fr").map { lang -> literal(name) + "@" + lang }
                    }
                    "VALUES ?an { ${names.joinToString(" ")} } ?a rdfs:label ?an ."
                }
                else -> return null
            }
            return """
                PREFIX wd: <http://www.wikidata.org/entity/>
                PREFIX wdt: <http://www.wikidata.org/prop/direct/>
                PREFIX p: <http://www.wikidata.org/prop/>
                PREFIX ps: <http://www.wikidata.org/prop/statement/>
                PREFIX pq: <http://www.wikidata.org/prop/qualifier/>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                SELECT DISTINCT ?work ?series ?ord ?seriesLabel (LANG(?seriesLabel) AS ?seriesLang) ?published WHERE {
                  $authorClause
                  VALUES ?t { ${titleValues.joinToString(" ") { literal(it) }} }
                  ?work wdt:P50 ?a ; rdfs:label ?l .
                  FILTER(LCASE(STR(?l)) = ?t)
                  OPTIONAL { ?work wdt:P577 ?published }
                  OPTIONAL {
                    ?work p:P179 ?st . ?st ps:P179 ?series .
                    OPTIONAL { ?st pq:P1545 ?ord }
                    ?series rdfs:label ?seriesLabel . FILTER(LANG(?seriesLabel) IN ("nl", "en", "de", "fr", "mul"))
                  }
                } LIMIT 50
            """.trimIndent()
        }

        fun parse(json: String, preferredLanguage: String?): WikidataWork? {
            val rows = Json.parseToJsonElement(json).jsonObject["results"]?.jsonObject?.get("bindings")?.jsonArray
                ?.map { binding -> (binding as JsonObject).mapValues { (_, v) -> v.str("value").orEmpty() } }
                ?: return null
            // Een werk mét serie heeft de voorkeur boven een gelijknamig werk zonder.
            val rowsByWork = rows.groupBy { it["work"].orEmpty().substringAfterLast('/') }
            val (workId, workRows) = rowsByWork.entries
                .sortedByDescending { (_, r) -> r.any { it["series"] != null } }
                .firstOrNull() ?: return null
            val languageOrder = listOfNotNull(preferredLanguage, "en", "mul", "nl", "de", "fr")
            val seriesRows = workRows.filter { it["series"] != null }
            val label = seriesRows.minByOrNull { row ->
                languageOrder.indexOf(row["seriesLang"]).let { if (it < 0) languageOrder.size else it }
            }
            return WikidataWork(
                workId = workId,
                seriesName = label?.get("seriesLabel"),
                seriesIndex = seriesRows.firstNotNullOfOrNull { it["ord"] },
                seriesId = seriesRows.firstOrNull()?.get("series")?.substringAfterLast('/'),
                // Soms staan er meerdere data (bijv. per land); de vroegste is de eerste uitgave.
                firstPublishedYear = workRows.mapNotNull { yearIn(it["published"]) }.minOrNull(),
            )
        }
    }
}
