package nl.wdhertog.library.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import nl.wdhertog.library.data.db.BookFormat
import nl.wdhertog.library.data.db.DatePrecision
import nl.wdhertog.library.data.db.DisposalReason
import nl.wdhertog.library.data.db.ReadingStatus
import nl.wdhertog.library.lookup.sources.arr
import nl.wdhertog.library.lookup.sources.int
import nl.wdhertog.library.lookup.sources.str
import nl.wdhertog.library.scan.Isbn
import java.time.LocalDate

data class HirtExport(
    val books: List<ImportedBook>,
    /** Leesdoel per jaar, bijv. 2026 → 16. */
    val goals: Map<Int, Int>,
    /** Boeken in het bestand die niet in je bezit zijn, of e-book/luisterboek. */
    val skipped: Int,
)

/**
 * Export van Have I Read That (zie ontwerp §6.11). Alleen boeken in bezit (`owned` of tag
 * `eigen-boeken`), geen e-books en luisterboeken.
 */
object HirtParser {
    const val SOURCE = "Have I Read That"
    private const val OWN_TAG = "eigen-boeken"
    private const val LENT_TAG = "geleend"
    private const val FAVORITE_TAG = "favorieten"

    /** "Duin (Duin, #1)" → titel "Duin", serie "Duin", deel "1". */
    private val seriesInTitle = Regex("""^(.*?)\s*\(([^()]*?),?\s*#\s*([\w.]+)\)\s*$""")
    private val contributor = Regex("""^(.*?)\s*\(([^()]*)\)$""")

    class FormatException(message: String) : Exception(message)

    /**
     * Met [includeAll] ook boeken die je niet (meer) hebt, plus e-books en luisterboeken (fase 7):
     * dan blijft de hele leesgeschiedenis bewaard.
     */
    fun parse(text: String, includeAll: Boolean = false): HirtExport {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: throw FormatException("Geen geldig JSON-bestand")
        val items = root["books"] as? JsonArray ?: throw FormatException("Geen Have I Read That-export (\"books\" ontbreekt)")
        val all = items.filterIsInstance<JsonObject>()
        val books = all.filter { includeAll || isOwnedPrintBook(it) }.mapNotNull(::toBook)
        val goals = (root["goals"] as? JsonObject).orEmpty().mapNotNull { (year, target) ->
            val y = year.toIntOrNull()
            val t = (target as? JsonPrimitive)?.intOrNull
            if (y != null && t != null) y to t else null
        }.toMap()
        return HirtExport(books, goals, skipped = all.size - books.size)
    }

    private fun isOwned(json: JsonObject): Boolean = json.str("owned") == "true" || OWN_TAG in tagsOf(json)

    private fun isOwnedPrintBook(json: JsonObject): Boolean = isOwned(json) && json.str("format") !in setOf("ebook", "audio")

    private fun toBook(json: JsonObject): ImportedBook? {
        var title = json.str("title") ?: return null
        var seriesName = json.str("series")
        var seriesIndex = json.str("series_number")
        seriesInTitle.matchEntire(title)?.let { m ->
            title = m.groupValues[1]
            if (seriesName == null) seriesName = m.groupValues[2].trim()
            if (seriesIndex == null) seriesIndex = m.groupValues[3]
        }
        title = title.trim().removeSuffix(":").trim()

        val authors = splitNames(json.str("author")).distinctBy(String::lowercase)
        // Rollen kloppen niet altijd; een auteur blijft auteur.
        val translators = (contributors(json.str("contributors"), "Translator") + splitNames(json.str("translator")))
            .distinctBy(String::lowercase)
            .filter { name -> authors.none { it.equals(name, ignoreCase = true) } }

        val tags = tagsOf(json) + splitNames(json.str("genre"))
        val status = json.str("status")
        return ImportedBook(
            title = title,
            authors = authors,
            translators = translators,
            isbn13 = json.str("isbn")?.let(Isbn::toIsbn13),
            format = when (json.str("format")) {
                "paperback" -> BookFormat.PAPERBACK
                "hardcover" -> BookFormat.HARDCOVER
                "ebook" -> BookFormat.EBOOK
                "audio" -> BookFormat.AUDIO
                else -> null
            },
            language = json.str("language"),
            originalTitle = json.str("original_title"),
            originalLanguage = json.str("original_language"),
            publisher = json.str("publisher"),
            publishedYear = json.int("published_year"),
            firstPublishedYear = json.int("first_published"),
            pages = json.int("pages")?.takeIf { it > 0 },
            description = json.str("description"),
            seriesName = seriesName,
            seriesIndex = seriesIndex,
            tags = tags.filter { it != OWN_TAG && it != LENT_TAG && it != FAVORITE_TAG }.distinctBy(String::lowercase),
            favorite = FAVORITE_TAG in tagsOf(json),
            awards = json.str("awards"),
            wantToRead = status == "to-read",
            // "geleend" bij je eigen boek: uitgeleend. Bij een boek dat je niet hebt: zelf geleend en teruggebracht.
            lentTo = if (LENT_TAG in tagsOf(json) && isOwned(json)) "" else null,
            owned = isOwned(json),
            disposalReason = if (LENT_TAG in tagsOf(json) && !isOwned(json)) DisposalReason.BORROWED else null,
            addedAt = json.str("date_added")?.let(::date),
            readings = readings(json, status),
            hirtId = json.str("id")?.toLongOrNull(),
            storygraphId = json.str("storygraph_id"),
        )
    }

    /**
     * Elke `reads`-regel wordt een leessessie. De laatste krijgt de status van het boek
     * (bezig / gestopt) en de beoordeling; "gelezen" zonder datums wordt één sessie zonder datum.
     */
    private fun readings(json: JsonObject, status: String?): List<ImportedReading> {
        val finalStatus = when (status) {
            "read" -> ReadingStatus.FINISHED
            "reading" -> ReadingStatus.READING
            "dnf" -> ReadingStatus.ABANDONED
            else -> null
        }
        val reads = json.arr("reads").orEmpty().filterIsInstance<JsonObject>()
            .sortedBy { read -> read.str("finished")?.let(::date) ?: read.str("started")?.let(::date) ?: LocalDate.MAX }
        val sessions = reads.mapIndexed { index, read ->
            val started = read.str("started")?.let(::date)
            val finished = read.str("finished")?.let(::date)
            val last = index == reads.lastIndex
            val sessionStatus = when {
                last && finalStatus != null && finalStatus != ReadingStatus.FINISHED -> finalStatus
                finished != null || finalStatus == ReadingStatus.FINISHED || !last -> ReadingStatus.FINISHED
                else -> ReadingStatus.READING
            }
            ImportedReading(
                sessionStatus,
                started,
                finished,
                precision(started, finished),
                // Voortgang telt alleen bij een boek dat je nog leest.
                progressPage = read.int("progress_page").takeIf { sessionStatus == ReadingStatus.READING },
                progressPercent = read.int("progress_percent").takeIf { sessionStatus == ReadingStatus.READING },
            )
        }.ifEmpty { listOfNotNull(finalStatus?.let { ImportedReading(it) }) }

        val rating = (json["rating"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }
        val review = json.str("review")
        if (sessions.isEmpty() || (rating == null && review == null)) return sessions
        return sessions.dropLast(1) + sessions.last().copy(rating = rating, review = review)
    }

    /** Oude leesdatums staan op 1 januari: dan is alleen het jaar bekend. */
    private fun precision(started: LocalDate?, finished: LocalDate?): DatePrecision {
        val date = finished ?: started ?: return DatePrecision.DAY
        return if (date.dayOfYear == 1) DatePrecision.YEAR else DatePrecision.DAY
    }

    private fun tagsOf(json: JsonObject): List<String> = splitNames(json.str("tags"))

    private fun splitNames(text: String?): List<String> =
        text?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

    /** "Max Schuchart (Translator), Pauline Baynes (Illustrator)" → namen met de gevraagde rol. */
    private fun contributors(text: String?, role: String): List<String> =
        splitNames(text).mapNotNull { entry ->
            contributor.matchEntire(entry)?.takeIf { it.groupValues[2].equals(role, ignoreCase = true) }?.groupValues?.get(1)
        }

    private fun date(text: String): LocalDate? = runCatching { LocalDate.parse(text.take(10)) }.getOrNull()

    private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()
}
