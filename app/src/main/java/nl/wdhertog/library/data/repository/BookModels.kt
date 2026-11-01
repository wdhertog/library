package nl.wdhertog.library.data.repository

import nl.wdhertog.library.data.db.Book
import nl.wdhertog.library.data.db.BookFormat
import nl.wdhertog.library.data.db.BookSeriesRow
import nl.wdhertog.library.data.db.EnrichStatus
import nl.wdhertog.library.data.db.Work

/** Titel voor weergave; een net gescand boek heeft nog geen titel. */
val Book.displayTitle: String get() = title.ifBlank { isbn13?.let { "ISBN $it" } ?: "Zonder titel" }

val Book.isAwaitingLookup: Boolean
    get() = isbn13 != null && (enrichStatus == EnrichStatus.NEW || enrichStatus == EnrichStatus.QUICK)

val EnrichStatus.label: String
    get() = when (this) {
        EnrichStatus.NEW -> "Nog niet opgezocht"
        EnrichStatus.QUICK -> "Snel opgezocht, details volgen"
        EnrichStatus.ENRICHED -> "Opgezocht"
        EnrichStatus.FAILED -> "Niet gevonden in de bronnen"
        EnrichStatus.MANUAL -> "Handmatig ingevoerd"
    }

data class BookListItem(
    val book: Book,
    val authors: List<String>,
    val series: BookSeriesRow?,
) {
    val authorLine: String get() = authors.joinToString(", ")
}

data class BookDetail(
    val book: Book,
    val works: List<Work>,
    val authors: List<String>,
    val series: List<BookSeriesRow>,
    val otherEditions: List<Book>,
)

/** Inhoud van het bewerkformulier; alles als tekst zoals de gebruiker het typt. */
data class BookDraft(
    val title: String = "",
    val subtitle: String = "",
    val authors: List<String> = listOf(""),
    val originalTitle: String = "",
    val isbn: String = "",
    val language: String = "",
    val publisher: String = "",
    val publishedYear: String = "",
    val pages: String = "",
    val format: BookFormat? = null,
    val seriesName: String = "",
    val seriesIndex: String = "",
    val tags: String = "",
    val description: String = "",
    val notes: String = "",
    val wantToRead: Boolean = false,
) {
    val cleanAuthors: List<String>
        get() = authors.map(String::trim).filter(String::isNotEmpty).distinctBy(String::lowercase)

    /** Velden die tegen automatisch bijwerken worden beschermd; notities en tags doet verrijking nooit. */
    fun changedFields(previous: BookDraft?): Set<String> {
        val fields = mapOf(
            "title" to { d: BookDraft -> d.title.trim() },
            "subtitle" to { d: BookDraft -> d.subtitle.trim() },
            "authors" to { d: BookDraft -> d.cleanAuthors.joinToString("|") },
            "originalTitle" to { d: BookDraft -> d.originalTitle.trim() },
            "isbn" to { d: BookDraft -> d.isbn.trim() },
            "language" to { d: BookDraft -> d.language },
            "publisher" to { d: BookDraft -> d.publisher.trim() },
            "publishedYear" to { d: BookDraft -> d.publishedYear.trim() },
            "pages" to { d: BookDraft -> d.pages.trim() },
            "format" to { d: BookDraft -> d.format?.name.orEmpty() },
            "series" to { d: BookDraft -> d.seriesName.trim() + "#" + d.seriesIndex.trim() },
            "description" to { d: BookDraft -> d.description.trim() },
        )
        val empty = BookDraft()
        return fields.filter { (_, value) ->
            val now = value(this)
            now != value(empty) && (previous == null || now != value(previous))
        }.keys
    }
}
