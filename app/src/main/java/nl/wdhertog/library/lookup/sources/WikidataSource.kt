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

/** Een deel van een serie volgens Wikidata. */
data class WikidataSeriesPart(val ordinal: String, val title: String, val year: Int?, val wikidataId: String)

data class WikidataSeriesParts(val seriesId: String, val parts: List<WikidataSeriesPart>)

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

    /**
     * Alle genummerde delen van een serie: via de serie-id als die bekend is, anders via de
     * Wikidata-id's van werken uit die serie. Null bij geen verbinding; leeg als Wikidata niets weet.
     */
    suspend fun seriesParts(seriesId: String?, workIds: List<String>, seriesName: String): WikidataSeriesParts? {
        val query = buildSeriesPartsQuery(seriesId, workIds) ?: return WikidataSeriesParts("", emptyList())
        val body = http.postForm(ENDPOINT, mapOf("query" to query), accept = "application/sparql-results+json") ?: return null
        return parseSeriesParts(body, workIds, seriesName)
    }

    companion object {
        private const val ENDPOINT = "https://query.wikidata.org/sparql"

        /** Een uitgeversreeks met honderden nummers is geen serie met "ontbrekende delen". */
        const val MAX_PARTS = 100

        fun buildSeriesPartsQuery(seriesId: String?, workIds: List<String>): String? {
            val start = when {
                seriesId != null && idPattern.matches(seriesId) -> "VALUES ?series { wd:$seriesId }"
                else -> {
                    val ids = workIds.filter { idPattern.matches(it) }.distinct()
                    if (ids.isEmpty()) return null
                    "VALUES ?w { ${ids.joinToString(" ") { "wd:$it" }} }\n  ?w wdt:P179 ?series ."
                }
            }
            // Vaste volgorde (optimizer uit): eerst de serie, dan de delen; anders zoekt Wikidata door álle series.
            return """
                PREFIX wd: <http://www.wikidata.org/entity/>
                PREFIX wdt: <http://www.wikidata.org/prop/direct/>
                PREFIX p: <http://www.wikidata.org/prop/>
                PREFIX ps: <http://www.wikidata.org/prop/statement/>
                PREFIX pq: <http://www.wikidata.org/prop/qualifier/>
                PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
                PREFIX hint: <http://www.bigdata.com/queryHints#>
                SELECT ?series ?seriesLabel ?part ?ord ?label (LANG(?label) AS ?lang) ?published WHERE {
                  hint:Query hint:optimizer "None" .
                  $start
                  ?st ps:P179 ?series .
                  ?part p:P179 ?st .
                  ?st pq:P1545 ?ord .
                  OPTIONAL { ?series rdfs:label ?seriesLabel . FILTER(LANG(?seriesLabel) IN ("nl", "en", "mul")) }
                  OPTIONAL { ?part rdfs:label ?label . FILTER(LANG(?label) IN ("nl", "en", "mul", "de", "fr")) }
                  OPTIONAL { ?part wdt:P577 ?published }
                } LIMIT 3000
            """.trimIndent()
        }

        /**
         * Kiest bij werken in meerdere series (bijv. een hoofdreeks en een deelreeks) de serie met de meeste
         * van [workIds], bij gelijkspel die met dezelfde naam, en anders de kleinste. Per nummer één deel:
         * bij voorkeur een Nederlandse titel.
         */
        fun parseSeriesParts(json: String, workIds: List<String>, seriesName: String): WikidataSeriesParts {
            val rows = Json.parseToJsonElement(json).jsonObject["results"]?.jsonObject?.get("bindings")?.jsonArray
                ?.map { binding -> (binding as JsonObject).mapValues { (_, v) -> v.str("value").orEmpty() } }
                .orEmpty()
            val bySeries = rows.groupBy { it["series"].orEmpty().substringAfterLast('/') }.filterKeys { it.isNotEmpty() }
            if (bySeries.isEmpty()) return WikidataSeriesParts("", emptyList())
            val own = workIds.toSet()
            val name = seriesName.trim().lowercase()
            val (seriesId, seriesRows) = bySeries.entries.sortedWith(
                compareByDescending<Map.Entry<String, List<Map<String, String>>>> { (_, r) -> r.map { it["part"].orEmpty().substringAfterLast('/') }.toSet().count { it in own } }
                    .thenByDescending { (_, r) -> r.any { it["seriesLabel"]?.lowercase() == name } }
                    .thenBy { (_, r) -> r.map { it["ord"] }.toSet().size },
            ).first()
            val languageOrder = listOf("nl", "en", "mul", "de", "fr")
            val parts = seriesRows.groupBy { it["ord"].orEmpty().trim() }.filterKeys { it.isNotEmpty() }.map { (ordinal, r) ->
                val best = r.filter { !it["label"].isNullOrBlank() }.minByOrNull { row ->
                    languageOrder.indexOf(row["lang"]).let { if (it < 0) languageOrder.size else it }
                }
                val partId = (best ?: r.first())["part"].orEmpty().substringAfterLast('/')
                WikidataSeriesPart(
                    ordinal = ordinal,
                    title = best?.get("label").orEmpty(),
                    year = r.filter { it["part"]?.endsWith("/$partId") == true }.mapNotNull { yearIn(it["published"]) }.minOrNull(),
                    wikidataId = partId,
                )
            }
            return WikidataSeriesParts(seriesId, if (parts.size > MAX_PARTS) emptyList() else parts)
        }
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
