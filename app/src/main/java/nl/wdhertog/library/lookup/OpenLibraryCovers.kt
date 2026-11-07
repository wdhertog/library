package nl.wdhertog.library.lookup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import nl.wdhertog.library.util.LanguageCodes
import nl.wdhertog.library.util.Languages

/** Covers om uit te kiezen; [byTitle] = het werk is op titel en auteur gevonden (dus niet zeker hetzelfde). */
data class CoverChoices(val candidates: List<CoverCandidate>, val byTitle: Boolean = false)

/** Een cover om uit te kiezen: klein voor de lijst, groot om op te slaan, en waar hij bij hoort. */
data class CoverCandidate(val id: Long, val label: String, val sameIsbn: Boolean) {
    val thumbnailUrl: String get() = "https://covers.openlibrary.org/b/id/$id-M.jpg"
    val url: String get() = "https://covers.openlibrary.org/b/id/$id-L.jpg"
}

/**
 * Andere covers bij Open Library: eerst die van deze editie (het ISBN), daarna van andere edities van
 * hetzelfde werk. Bij een ISBN staat meestal maar één cover, en die hoort niet altijd bij jouw druk.
 * Alleen het ISBN en het werk-id gaan naar Open Library.
 */
class OpenLibraryCovers(private val http: LookupHttp) {

    /**
     * [language]: ISO 639-1 van het boek; edities in die taal komen eerst. [otherIsbns]: ISBN's van je andere
     * edities van hetzelfde werk; kent Open Library dit ISBN of het werk niet, dan vinden we het werk daarlangs.
     */
    suspend fun candidates(
        isbn13: String?,
        workId: String?,
        language: String?,
        otherIsbns: List<String> = emptyList(),
        /** Laatste redmiddel: titels (bijv. de eigen en de oorspronkelijke) en de eerste auteur. */
        titles: List<String> = emptyList(),
        author: String? = null,
        limit: Int = 60,
    ): CoverChoices {
        val edition = isbn13?.let { http.get("$BASE/isbn/$it.json") }?.let(::parseEdition)
        val known = workId ?: edition?.workId ?: otherIsbns.take(3).firstNotNullOfOrNull { other ->
            http.get("$BASE/isbn/$other.json")?.let(::parseEdition)?.workId
        }
        val byTitle = if (known == null && edition?.covers.isNullOrEmpty()) {
            titles.filter(String::isNotBlank).distinct().take(2).firstNotNullOfOrNull { title -> searchWork(title, author) }
        } else {
            null
        }
        val work = known ?: byTitle
        val others = work?.let { http.get("$BASE/works/$it/editions.json?limit=300") }?.let(::parseEditions).orEmpty()
        return CoverChoices(combine(edition, others, language, limit), byTitle = byTitle != null)
    }

    /** Het werk zoeken op titel (en auteur); stuurt alleen die naar Open Library. */
    private suspend fun searchWork(title: String, author: String?): String? {
        val query = buildString {
            append("$BASE/search.json?fields=key,title,cover_i&limit=5&title=").append(encode(title))
            author?.takeIf(String::isNotBlank)?.let { append("&author=").append(encode(it)) }
        }
        return http.get(query)?.let { parseSearch(it, title) }
    }

    private fun encode(text: String) = java.net.URLEncoder.encode(text, "UTF-8")

    internal data class Edition(val covers: List<Long>, val label: String, val workId: String?, val language: String? = null)

    companion object {
        private const val BASE = "https://openlibrary.org"

        internal fun combine(edition: Edition?, others: List<Edition>, language: String?, limit: Int): List<CoverCandidate> {
            val own = edition?.covers.orEmpty().map { CoverCandidate(it, "Dit ISBN", sameIsbn = true) }
            val rest = others.sortedByDescending { language != null && it.language == language }
                .flatMap { e -> e.covers.map { CoverCandidate(it, e.label, sameIsbn = false) } }
            return (own + rest).distinctBy { it.id }.take(limit)
        }

        /** Het werk uit een zoekresultaat: liefst met precies deze titel, anders het eerste met een cover. */
        internal fun parseSearch(json: String, title: String): String? {
            val docs = (Json.parseToJsonElement(json).jsonObject["docs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            fun key(doc: JsonObject) = (doc["key"] as? JsonPrimitive)?.contentOrNull?.substringAfterLast('/')
            fun norm(text: String?) = text?.let(nl.wdhertog.library.util.TextNormalizer::titleSortKey)
            val exact = docs.firstOrNull { norm((it["title"] as? JsonPrimitive)?.contentOrNull) == norm(title) }
            return key(exact ?: docs.firstOrNull { it["cover_i"] != null } ?: return null)
        }

        internal fun parseEdition(json: String): Edition = edition(Json.parseToJsonElement(json).jsonObject)

        internal fun parseEditions(json: String): List<Edition> =
            (Json.parseToJsonElement(json).jsonObject["entries"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.let(::edition) }
                .filter { it.covers.isNotEmpty() }

        private fun edition(e: JsonObject): Edition {
            fun str(key: String) = (e[key] as? JsonPrimitive)?.contentOrNull
            fun firstOf(key: String) = ((e[key] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull
            val covers = (e["covers"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }
                .filter { it > 0 } // -1 = geen cover
            val language = ((e["languages"] as? JsonArray)?.firstOrNull() as? JsonObject)
                ?.let { (it["key"] as? JsonPrimitive)?.contentOrNull }?.substringAfterLast('/')
            val iso = LanguageCodes.toIso1(language)
            val label = listOfNotNull(firstOf("publishers"), str("publish_date"), str("physical_format"), iso?.let(Languages::displayName))
                .joinToString(" · ").ifEmpty { "Andere editie" }
            val work = ((e["works"] as? JsonArray)?.firstOrNull() as? JsonObject)
                ?.let { (it["key"] as? JsonPrimitive)?.contentOrNull }?.substringAfterLast('/')
            return Edition(covers, label, work, iso)
        }
    }
}
