package nl.wdhertog.library.importer

import nl.wdhertog.library.data.db.BookFormat
import nl.wdhertog.library.data.db.DatePrecision
import nl.wdhertog.library.data.db.ReadingStatus
import nl.wdhertog.library.scan.Isbn
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Uitkomst van een Goodreads-export: de boeken, en hoeveel ervan als "in bezit" gemarkeerd waren. */
data class GoodreadsExport(val books: List<ImportedBook>, val ownedMarked: Int)

/**
 * De CSV-export van Goodreads (My Books → Import and export → Export Library), ontwerp §12.4.
 * Leest de bekende kolommen op naam; ontbrekende kolommen zijn geen probleem.
 *
 * In bezit: Goodreads houdt dat zelden bij ("Owned Copies" en de plank "owned"). Heeft niemand dat
 * gemarkeerd, dan gelden alle boeken als in bezit; anders alleen de gemarkeerde, en de rest komt mee als
 * "niet (meer) in bezit", zodat de leesgeschiedenis niet verloren gaat.
 */
object GoodreadsParser {
    const val SOURCE = "Goodreads"

    private val dateFormats = listOf(DateTimeFormatter.ofPattern("yyyy/MM/dd"), DateTimeFormatter.ISO_LOCAL_DATE)

    /** Planken die Goodreads zelf heeft; geen tags. */
    private val systemShelves = setOf("read", "to-read", "currently-reading", "owned", "did-not-finish", "dnf")

    /** Een Goodreads-export herken je aan deze kolommen. */
    fun matches(header: List<String>): Boolean {
        val names = header.map { it.trim().lowercase() }.toSet()
        return "book id" in names && "exclusive shelf" in names && "title" in names
    }

    fun parse(table: Table): GoodreadsExport {
        val index = table.header.withIndex().associate { (i, name) -> name.trim().lowercase() to i }
        fun List<String>.col(name: String): String? = index[name.lowercase()]?.let { getOrNull(it) }?.trim()?.takeIf(String::isNotEmpty)

        val rows = table.rows.filter { it.col("Title") != null }
        val ownedFlags = rows.map { row ->
            (row.col("Owned Copies")?.toIntOrNull() ?: 0) > 0 || "owned" in shelves(row.col("Bookshelves"))
        }
        val anyOwned = ownedFlags.any { it }
        val books = rows.mapIndexed { i, row -> toBook({ row.col(it) }, owned = !anyOwned || ownedFlags[i]) }
        return GoodreadsExport(books, ownedFlags.count { it })
    }

    private fun toBook(col: (String) -> String?, owned: Boolean): ImportedBook {
        val parts = ImportText.splitSeries(col("Title")!!)
        val shelf = col("Exclusive Shelf")?.lowercase()
        val shelves = shelves(col("Bookshelves"))
        val authors = (listOfNotNull(col("Author")) + col("Additional Authors").orEmpty().split(','))
            .map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)
        return ImportedBook(
            title = parts.title,
            authors = authors,
            isbn13 = isbn(col("ISBN13")) ?: isbn(col("ISBN")),
            format = format(col("Binding")),
            publisher = col("Publisher"),
            publishedYear = col("Year Published")?.toIntOrNull(),
            firstPublishedYear = col("Original Publication Year")?.toIntOrNull(),
            pages = col("Number of Pages")?.toIntOrNull()?.takeIf { it > 0 },
            seriesName = parts.seriesName,
            seriesIndex = parts.seriesIndex,
            tags = shelves.filter { it !in systemShelves },
            wantToRead = shelf == "to-read",
            addedAt = col("Date Added")?.let(::date),
            readings = readings(col, shelf),
            owned = owned,
            notes = col("Private Notes"),
            externalIds = listOfNotNull(col("Book Id")?.let(ExternalIds::goodreads)),
        )
    }

    /**
     * "read": één sessie op de leesdatum (Goodreads bewaart alleen de laatste), plus een sessie zonder datum
     * voor elke keer dat je het daarvoor las (Read Count). "currently-reading": een lopende sessie.
     */
    private fun readings(col: (String) -> String?, shelf: String?): List<ImportedReading> {
        val rating = col("My Rating")?.toDoubleOrNull()?.takeIf { it > 0 }
        val review = col("My Review")
        return when (shelf) {
            "read" -> {
                val finished = col("Date Read")?.let(::date)
                val earlier = ((col("Read Count")?.toIntOrNull() ?: 1) - 1).coerceIn(0, 20)
                List(earlier) { ImportedReading(ReadingStatus.FINISHED) } +
                    ImportedReading(ReadingStatus.FINISHED, finishedAt = finished, datePrecision = DatePrecision.DAY, rating = rating, review = review)
            }
            "currently-reading" -> listOf(ImportedReading(ReadingStatus.READING, rating = rating, review = review))
            "did-not-finish", "dnf" -> listOf(ImportedReading(ReadingStatus.ABANDONED, rating = rating, review = review))
            else -> emptyList()
        }
    }

    private fun shelves(text: String?): List<String> =
        text.orEmpty().split(',').map { it.trim() }.filter(String::isNotEmpty)

    /** Goodreads zet ISBN's als ="9781234567890" (zodat Excel er geen getal van maakt). */
    internal fun isbn(text: String?): String? =
        text?.removePrefix("=")?.trim('"')?.trim()?.takeIf(String::isNotEmpty)?.let(Isbn::toIsbn13)

    private fun format(binding: String?): BookFormat? {
        val b = binding?.lowercase() ?: return null
        return when {
            "hardcover" in b || "hardback" in b -> BookFormat.HARDCOVER
            "paperback" in b -> BookFormat.PAPERBACK
            "kindle" in b || "ebook" in b || "e-book" in b || "nook" in b -> BookFormat.EBOOK
            "audio" in b -> BookFormat.AUDIO
            else -> null
        }
    }

    private fun date(text: String): LocalDate? =
        dateFormats.firstNotNullOfOrNull { runCatching { LocalDate.parse(text.take(10), it) }.getOrNull() }
}
