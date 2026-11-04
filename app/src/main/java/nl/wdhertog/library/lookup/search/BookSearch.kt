package nl.wdhertog.library.lookup.search

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import nl.wdhertog.library.lookup.LookupException
import nl.wdhertog.library.lookup.LookupHttp
import nl.wdhertog.library.lookup.sources.arr
import nl.wdhertog.library.lookup.sources.str
import nl.wdhertog.library.lookup.sources.yearIn
import nl.wdhertog.library.scan.Isbn
import java.net.URLEncoder

/**
 * Zoeken op titel en auteur (voor cover-herkenning): de KB voor Nederlandstalige boeken en
 * Open Library voor de rest, tegelijk. De KB zoekt per woord in de titel; dat duurt een paar seconden.
 */
class BookSearch(
    private val http: LookupHttp,
    /** Instellingen: KB en Open Library kunnen uit staan. */
    private val enabled: (nl.wdhertog.library.lookup.LookupSource) -> Boolean = { true },
) {

    /**
     * Eerst nauwkeurig: per woord een titel met dat woord én een van de andere woorden in titel of auteur.
     * Levert de KB zo niets op, dan breder: alleen het woord in de titel.
     */
    suspend fun search(query: String, words: List<String>): List<SearchCandidate> = coroutineScope {
        val useKb = enabled(nl.wdhertog.library.lookup.LookupSource.KB)
        val openLibrary = async { if (enabled(nl.wdhertog.library.lookup.LookupSource.OPEN_LIBRARY)) safe { openLibrary(query) } else emptyList() }
        var kb = if (!useKb) emptyList() else words.map { word -> async { safe { kb(word, words - word) } } }.awaitAll().flatten()
        if (useKb && kb.isEmpty() && words.size > 1) kb = words.map { word -> async { safe { kb(word, emptyList()) } } }.awaitAll().flatten()
        CoverText.rank(kb + openLibrary.await(), query)
    }

    private suspend fun kb(titleWord: String, otherWords: List<String>): List<SearchCandidate> {
        val query = kbQuery(titleWord, otherWords) ?: return emptyList()
        val body = http.postForm(
            KB_ENDPOINT,
            mapOf("query" to query),
            accept = "application/sparql-results+json",
        ) ?: return emptyList()
        return parseKb(body)
    }

    private suspend fun openLibrary(query: String): List<SearchCandidate> {
        val url = "https://openlibrary.org/search.json?q=${URLEncoder.encode(query, "UTF-8")}" +
            "&fields=title,subtitle,author_name,first_publish_year,isbn,cover_i&limit=10"
        return parseOpenLibrary(http.get(url) ?: return emptyList())
    }

    /** Eén bron die niet bereikbaar is, mag de andere niet tegenhouden. */
    private suspend fun safe(block: suspend () -> List<SearchCandidate>): List<SearchCandidate> =
        try {
            block()
        } catch (_: LookupException) {
            emptyList()
        }

    companion object {
        private const val KB_ENDPOINT = "https://data.bibliotheken.nl/sparql"
        /** Alleen letters en cijfers komen in de SPARQL-tekst. */
        private fun clean(word: String) = word.filter(Char::isLetterOrDigit)

        internal fun kbQuery(titleWord: String, otherWords: List<String>): String? {
            val title = clean(titleWord).ifEmpty { return null }
            val others = otherWords.map(::clean).filter(String::isNotEmpty)
            val alsoOther = if (others.isEmpty()) {
                ""
            } else {
                "FILTER(" + others.joinToString(" || ") { w ->
                    "CONTAINS(LCASE(STR(?name)), \"$w\") || CONTAINS(LCASE(STR(?an)), \"$w\")"
                } + ")"
            }
            return """
                PREFIX schema: <http://schema.org/>
                SELECT ?name ?isbn (SAMPLE(?an) AS ?author) (SAMPLE(?d) AS ?date) WHERE {
                  ?b schema:name ?name ; schema:isbn ?isbn .
                  FILTER(CONTAINS(LCASE(STR(?name)), "$title"))
                  OPTIONAL { ?b schema:author/schema:name ?an }
                  $alsoOther
                  OPTIONAL { ?b schema:datePublished ?d }
                } GROUP BY ?name ?isbn LIMIT 60
            """.trimIndent()
        }

        internal fun parseKb(body: String): List<SearchCandidate> {
            val rows = (Json.parseToJsonElement(body).jsonObject["results"] as? JsonObject)?.get("bindings") as? JsonArray ?: return emptyList()
            return rows.filterIsInstance<JsonObject>().mapNotNull { row ->
                fun value(key: String) = ((row[key] as? JsonObject)?.get("value") as? JsonPrimitive)?.contentOrNull?.trim()
                val name = value("name") ?: return@mapNotNull null
                SearchCandidate(
                    title = name.substringBefore(" : ").trim(),
                    subtitle = name.substringAfter(" : ", "").trim().ifEmpty { null },
                    authors = listOfNotNull(value("author")),
                    isbn13 = value("isbn")?.let(Isbn::toIsbn13),
                    year = yearIn(value("date")),
                    source = "KB",
                )
            }
        }

        internal fun parseOpenLibrary(body: String): List<SearchCandidate> {
            val docs = Json.parseToJsonElement(body).arr("docs") ?: return emptyList()
            return docs.filterIsInstance<JsonObject>().mapNotNull { doc ->
                val title = doc.str("title") ?: return@mapNotNull null
                val isbns = doc.arr("isbn")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                SearchCandidate(
                    title = title,
                    subtitle = doc.str("subtitle"),
                    authors = doc.arr("author_name")?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().take(3),
                    // Het werk heeft vaak tientallen ISBN's (alle edities); de eerste moderne is goed genoeg om op te zoeken.
                    isbn13 = isbns.firstNotNullOfOrNull { raw -> Isbn.toIsbn13(raw)?.takeIf { raw.length == 13 } }
                        ?: isbns.firstNotNullOfOrNull(Isbn::toIsbn13),
                    year = doc.str("first_publish_year")?.toIntOrNull(),
                    source = "Open Library",
                    coverUrl = doc.str("cover_i")?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg" },
                )
            }
        }
    }
}
