package nl.wdhertog.library.importer

import nl.wdhertog.library.data.db.BookFormat
import nl.wdhertog.library.data.db.DatePrecision
import nl.wdhertog.library.data.db.ReadingStatus
import nl.wdhertog.library.lookup.BookMetadata
import java.time.LocalDate

/** Eén boek uit een importbestand, los van het formaat (Have I Read That, CSV, Excel). */
data class ImportedBook(
    val title: String,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val translators: List<String> = emptyList(),
    val isbn13: String? = null,
    val format: BookFormat? = null,
    val language: String? = null,
    val originalTitle: String? = null,
    val originalLanguage: String? = null,
    val publisher: String? = null,
    val publishedYear: Int? = null,
    val firstPublishedYear: Int? = null,
    val pages: Int? = null,
    val description: String? = null,
    val seriesName: String? = null,
    val seriesIndex: String? = null,
    val tags: List<String> = emptyList(),
    val awards: String? = null,
    val wantToRead: Boolean = false,
    /** Uitgeleend aan iemand (naam onbekend = lege string). */
    val lentTo: String? = null,
    val addedAt: LocalDate? = null,
    val readings: List<ImportedReading> = emptyList(),
    val hirtId: Long? = null,
    val storygraphId: String? = null,
    /** false = staat in het bestand, maar is niet (meer) in bezit. */
    val owned: Boolean = true,
    /** Pad in de locatieboom, bijv. "Woonkamer/Kast 2/Plank 3". */
    val locationPath: String? = null,
    val notes: String? = null,
)

data class ImportedReading(
    val status: ReadingStatus,
    val startedAt: LocalDate? = null,
    val finishedAt: LocalDate? = null,
    val datePrecision: DatePrecision = DatePrecision.DAY,
    val rating: Double? = null,
    val review: String? = null,
)

/** Wat de bestaande verrijkingslogica kan wegschrijven (zie BookRepository.applyMetadata). */
fun ImportedBook.toMetadata(source: String) = BookMetadata(
    source = source,
    title = title,
    subtitle = subtitle,
    authors = authors,
    translators = translators,
    language = language,
    originalTitle = originalTitle,
    originalLanguage = originalLanguage,
    publisher = publisher,
    publishedYear = publishedYear,
    firstPublishedYear = firstPublishedYear,
    pages = pages,
    format = format,
    description = description,
    seriesName = seriesName,
    seriesIndex = seriesIndex,
)
