package nl.wdhertog.library.lookup.sources

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import nl.wdhertog.library.data.db.BookFormat
import nl.wdhertog.library.lookup.BookMetadata
import nl.wdhertog.library.lookup.LookupHttp
import nl.wdhertog.library.util.LanguageCodes

/** Open Library (openlibrary.org): sterk voor Engelstalige boeken, levert ook covers en werk-id's. */
class OpenLibrarySource(private val http: LookupHttp) : BookSource {
    override val name = "Open Library"

    override suspend fun quickLookup(isbn13: String): BookMetadata? =
        http.get("$BASE/api/books?bibkeys=ISBN:$isbn13&format=json&jscmd=data")?.let { parseData(it, isbn13) }

    /**
     * De auteurs van een werk bij Open Library met hun Wikidata-id (als Open Library dat kent): naam → id.
     * Null bij geen verbinding.
     */
    suspend fun workAuthorWikidataIds(workId: String): List<Pair<String, String?>>? {
        val work = http.get("$BASE/works/$workId.json") ?: return null
        val keys = Json.parseToJsonElement(work).jsonObject.arr("authors").orEmpty()
            .mapNotNull { (it as? JsonObject)?.obj("author")?.str("key") }.take(5)
        return keys.mapNotNull { key -> http.get("$BASE$key.json")?.let(::parseAuthorRecord) }
    }

    /** De onderwerpen ("subjects") van een werk; null bij geen verbinding. */
    suspend fun workSubjects(workId: String): List<String>? =
        http.get("$BASE/works/$workId.json")?.let(::parseSubjects)

    override suspend fun lookup(isbn13: String): BookMetadata? {
        val data = quickLookup(isbn13) ?: return null
        val edition = http.get("$BASE/isbn/$isbn13.json")?.let(::parseEdition)
        val work = edition?.openLibraryWorkId?.let { http.get("$BASE/works/$it.json") }?.let(::parseWork)
        return data.copy(
            language = edition?.language,
            format = edition?.format,
            seriesName = edition?.seriesName,
            seriesIndex = edition?.seriesIndex,
            openLibraryWorkId = edition?.openLibraryWorkId,
            description = work?.description,
            firstPublishedYear = work?.firstPublishedYear,
        )
    }

    companion object {
        private const val BASE = "https://openlibrary.org"

        fun parseData(json: String, isbn13: String): BookMetadata? {
            val book = Json.parseToJsonElement(json).obj("ISBN:$isbn13") ?: return null
            return BookMetadata(
                source = "Open Library",
                title = book.str("title"),
                subtitle = book.str("subtitle"),
                authors = book.arr("authors").orEmpty().mapNotNull { it.str("name") },
                publisher = book.arr("publishers")?.firstOrNull()?.str("name"),
                publishedYear = yearIn(book.str("publish_date")),
                pages = book.int("number_of_pages").plausiblePages(),
                coverUrl = book.obj("cover")?.let { it.str("large") ?: it.str("medium") }?.replace("http://", "https://"),
                openLibraryEditionId = book.obj("identifiers").arr("openlibrary")?.firstOrNull()
                    ?.let { (it as? JsonPrimitive)?.contentOrNull },
            ).takeIf { it.hasContent }
        }

        fun parseEdition(json: String): BookMetadata {
            val edition = Json.parseToJsonElement(json).jsonObject
            val series = (edition.arr("series")?.firstOrNull() as? JsonPrimitive)?.contentOrNull
            val seriesMatch = series?.let { Regex("""^(.*?)[\s,;:#(-]+(?:no\.?|vol\.?|book|deel|#)?\s*(\d+(?:\.\d+)?)\)?$""", RegexOption.IGNORE_CASE).find(it.trim()) }
            return BookMetadata(
                source = "Open Library",
                language = LanguageCodes.toIso1(edition.arr("languages")?.firstOrNull()?.str("key")),
                format = when (edition.str("physical_format")?.lowercase()) {
                    "paperback", "softcover", "trade paperback" -> BookFormat.PAPERBACK
                    "hardcover", "hardback" -> BookFormat.HARDCOVER
                    "mass market paperback", "pocket book" -> BookFormat.POCKET
                    else -> null
                },
                seriesName = (seriesMatch?.groupValues?.get(1) ?: series)?.trim()?.takeIf(String::isNotEmpty),
                seriesIndex = seriesMatch?.groupValues?.get(2),
                openLibraryWorkId = edition.arr("works")?.firstOrNull()?.str("key")?.substringAfterLast('/'),
            )
        }

        /** Naam en Wikidata-id van een Open Library-auteur. */
        fun parseAuthorRecord(json: String): Pair<String, String?>? {
            val author = Json.parseToJsonElement(json).jsonObject
            val name = author.str("name") ?: return null
            return name to author.obj("remote_ids")?.str("wikidata")?.takeIf { it.matches(Regex("Q\\d+")) }
        }

        fun parseSubjects(json: String): List<String> =
            Json.parseToJsonElement(json).jsonObject.arr("subjects").orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

        fun parseWork(json: String): BookMetadata {
            val work = Json.parseToJsonElement(json).jsonObject
            val description = when (val d = work["description"]) {
                is JsonPrimitive -> d.contentOrNull
                is JsonObject -> d.str("value")
                else -> null
            }
            return BookMetadata(
                source = "Open Library",
                description = description?.trim()?.takeIf(String::isNotEmpty),
                firstPublishedYear = yearIn(work.str("first_publish_date")),
            )
        }
    }
}
