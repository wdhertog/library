package nl.wdhertog.library.data.backup

import nl.wdhertog.library.data.db.DatePrecision
import nl.wdhertog.library.data.db.Ownership
import nl.wdhertog.library.data.db.ReadingSession
import nl.wdhertog.library.data.db.ReadingStatus
import nl.wdhertog.library.data.repository.BookListItem
import java.time.ZoneId

/**
 * Alle boeken als CSV voor Excel en andere programma's: puntkomma's en UTF-8 met BOM, zoals Excel in
 * Nederland verwacht. De kolomnamen herkent de eigen import ook weer (zie ColumnTarget).
 */
object CsvExport {

    val header = listOf(
        "Titel", "Ondertitel", "Auteur", "Vertaler", "ISBN", "Uitgever", "Jaar", "Pagina's", "Taal", "Formaat",
        "Serie", "Deel", "Locatie", "Eigenaar", "Niet meer in bezit", "Weggegeven aan", "Uitgeleend aan",
        "Tags", "Favoriet", "Wil ik lezen", "Gelezen", "Keer gelezen", "Waardering", "Notities", "Toegevoegd",
    )

    fun write(
        books: List<BookListItem>,
        locationPath: (Long) -> String?,
        sessions: Map<Long, List<ReadingSession>>,
        lentTo: Map<Long, String?>,
    ): String {
        val rows = books.sortedBy { it.book.title.lowercase() }.map { item ->
            val book = item.book
            val finished = sessions[book.id].orEmpty().filter { it.status == ReadingStatus.FINISHED }
            val last = finished.maxByOrNull { it.finishedAt?.toEpochDay() ?: Long.MIN_VALUE }
            listOf(
                book.title,
                book.subtitle,
                item.authors.joinToString("; "),
                book.translators,
                book.isbn13 ?: book.isbn10,
                book.publisher,
                book.publishedYear?.toString(),
                book.pages?.toString(),
                book.language,
                book.format?.label,
                item.series?.seriesName,
                item.series?.seriesIndex,
                book.locationId?.let(locationPath)?.replace(" › ", "/"),
                book.owner,
                if (book.ownership == Ownership.NOT_OWNED) "ja" else null,
                book.disposedTo,
                if (book.id in lentTo) lentTo[book.id] ?: "ja" else null,
                book.tags,
                if (book.favorite) "ja" else null,
                if (book.wantToRead) "ja" else null,
                when {
                    last?.finishedAt == null -> if (finished.isNotEmpty()) "ja" else null
                    last.datePrecision == DatePrecision.YEAR -> last.finishedAt.year.toString()
                    else -> last.finishedAt.toString()
                },
                finished.size.takeIf { it > 0 }?.toString(),
                last?.rating?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString().replace('.', ',') },
                book.notes,
                book.addedAt.atZone(ZoneId.systemDefault()).toLocalDate().toString(),
            )
        }
        return "﻿" + (listOf(header) + rows).joinToString("\r\n") { row -> row.joinToString(";") { field(it) } } + "\r\n"
    }

    private fun field(value: String?): String {
        val text = value.orEmpty()
        return if (text.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }
}
