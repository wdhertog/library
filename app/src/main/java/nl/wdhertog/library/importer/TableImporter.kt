package nl.wdhertog.library.importer

import nl.wdhertog.library.data.db.ReadingStatus
import nl.wdhertog.library.lookup.sources.yearIn
import nl.wdhertog.library.scan.Isbn
import nl.wdhertog.library.util.LanguageCodes
import nl.wdhertog.library.util.TextNormalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Waar een kolom uit het bestand terechtkomt. */
enum class ColumnTarget(val label: String, private val names: List<String> = emptyList()) {
    IGNORE("Niet importeren"),
    TITLE("Titel", listOf("titel", "title", "boektitel", "naam boek")),
    SUBTITLE("Ondertitel", listOf("ondertitel", "subtitle")),
    AUTHOR("Auteur (volledige naam)", listOf("auteur", "auteurs", "author", "authors", "schrijver", "writer")),
    AUTHOR_FIRST("Auteur: voornaam", listOf("voornaam", "voornamen", "first name", "firstname", "voorletters")),
    AUTHOR_INFIX("Auteur: tussenvoegsel", listOf("tussenvoegsel", "tussenvoegsels", "voorvoegsel")),
    AUTHOR_LAST("Auteur: achternaam", listOf("achternaam", "last name", "lastname", "surname", "familienaam")),
    ISBN("ISBN", listOf("isbn", "isbn13", "isbn 13", "isbn-13", "isbn10", "ean")),
    PUBLISHER("Uitgever", listOf("uitgever", "uitgeverij", "publisher")),
    YEAR("Jaar van uitgave", listOf("jaar", "jaar van uitgave", "uitgavejaar", "publicatiejaar", "year", "year published", "published")),
    PAGES("Pagina's", listOf("pagina's", "paginas", "pagina", "aantal pagina's", "pages", "number of pages")),
    LANGUAGE("Taal", listOf("taal", "language")),
    SERIES("Serie", listOf("serie", "reeks", "series")),
    SERIES_INDEX("Deel in serie", listOf("deel", "nummer", "volume", "series number", "deel in serie", "serienummer")),
    LOCATION("Locatie (bijv. Woonkamer/Kast 2/Plank 3)", listOf("locatie", "plek", "plaats", "kast", "location", "shelf")),
    TAGS("Tags (komma-gescheiden)", listOf("tags", "genre", "genres", "categorie", "labels", "bookshelves")),
    TAG_IF_TRUE("Tag als 1/ja (de kolomnaam)"),
    READ("Gelezen (ja/nee of datum)", listOf("gelezen", "read", "gelezen op", "date read", "datum gelezen")),
    RATING("Waardering (0-5)", listOf("waardering", "beoordeling", "rating", "sterren", "score", "my rating")),
    REVIEW("Recensie", listOf("recensie", "review", "my review")),
    LENT_TO("Uitgeleend (aan)", listOf("uitgeleend", "uitgeleend aan", "lent", "lent to", "geleend aan")),
    GONE("Niet meer in bezit (1/ja)", listOf("is al weg", "weg", "verkocht", "weggedaan", "niet meer in bezit", "weggegeven")),
    NOTES("Notities", listOf("notities", "notitie", "opmerkingen", "opmerking", "notes", "note")),
    NOTE_WITH_HEADER("Notitie \"kolomnaam: waarde\""),
    ;

    companion object {
        /** Eerste gok op naam van de kolom; een onbekende ja/nee-kolom wordt een tag. */
        fun guess(header: String, values: List<String>): ColumnTarget {
            val key = TextNormalizer.normalize(header).trim()
            entries.firstOrNull { target -> target.names.any { TextNormalizer.normalize(it) == key } }?.let { return it }
            val filled = values.filter(String::isNotBlank)
            return if (filled.isNotEmpty() && filled.all { parseBoolean(it) != null }) TAG_IF_TRUE else IGNORE
        }

        /** 1/0, ja/nee, yes/no, x; null als het geen ja/nee is. */
        fun parseBoolean(text: String): Boolean? = when (TextNormalizer.normalize(text)) {
            "1", "ja", "j", "yes", "y", "true", "waar", "x" -> true
            "0", "nee", "n", "no", "false", "onwaar" -> false
            else -> null
        }
    }
}

data class TableBooks(val books: List<ImportedBook>, val skipped: Int)

/** Rijen van een tabel → boeken, volgens de gekozen kolomtoewijzing. */
object TableImporter {

    private val dateFormats = listOf("yyyy-MM-dd", "d-M-yyyy", "d/M/yyyy", "yyyy/MM/dd", "d.M.yyyy").map(DateTimeFormatter::ofPattern)

    fun toBooks(table: Table, targets: List<ColumnTarget>): TableBooks {
        val books = table.rows.mapNotNull { row -> toBook(table.header, row, targets) }
        return TableBooks(books, skipped = table.rows.size - books.size)
    }

    private fun toBook(header: List<String>, row: List<String>, targets: List<ColumnTarget>): ImportedBook? {
        fun all(target: ColumnTarget) = targets.indices.filter { targets[it] == target }.map { row.getOrElse(it) { "" }.trim() }
        fun first(target: ColumnTarget) = all(target).firstOrNull(String::isNotEmpty)

        val title = first(ColumnTarget.TITLE) ?: return null
        val tags = all(ColumnTarget.TAGS).flatMap { it.split(',', ';') }.map(String::trim).filter(String::isNotEmpty) +
            targets.indices.filter { targets[it] == ColumnTarget.TAG_IF_TRUE && ColumnTarget.parseBoolean(row.getOrElse(it) { "" }) == true }
                .map { header[it].trim() }
        val notes = all(ColumnTarget.NOTES).filter(String::isNotEmpty) +
            targets.indices.filter { targets[it] == ColumnTarget.NOTE_WITH_HEADER && row.getOrElse(it) { "" }.isNotBlank() }
                .map { "${header[it].trim()}: ${row[it].trim()}" }

        val rating = first(ColumnTarget.RATING)?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it > 0 }?.coerceAtMost(5.0)
        val read = first(ColumnTarget.READ)
        val readDate = read?.let(::date)
        val hasRead = readDate != null || read?.let(ColumnTarget::parseBoolean) == true || (read == null && rating != null)
        val readings = if (hasRead) {
            listOf(ImportedReading(ReadingStatus.FINISHED, finishedAt = readDate, rating = rating, review = first(ColumnTarget.REVIEW)))
        } else {
            emptyList()
        }

        val lent = first(ColumnTarget.LENT_TO)?.let { value ->
            when (ColumnTarget.parseBoolean(value)) {
                true -> ""
                false -> null
                null -> value
            }
        }

        return ImportedBook(
            title = title,
            subtitle = first(ColumnTarget.SUBTITLE),
            authors = authors(first(ColumnTarget.AUTHOR), first(ColumnTarget.AUTHOR_FIRST), first(ColumnTarget.AUTHOR_INFIX), first(ColumnTarget.AUTHOR_LAST)),
            isbn13 = first(ColumnTarget.ISBN)?.let(Isbn::toIsbn13),
            publisher = first(ColumnTarget.PUBLISHER),
            publishedYear = yearIn(first(ColumnTarget.YEAR)),
            pages = first(ColumnTarget.PAGES)?.toIntOrNull()?.takeIf { it in 1..5000 },
            language = first(ColumnTarget.LANGUAGE)?.let(::languageCode),
            seriesName = first(ColumnTarget.SERIES),
            seriesIndex = first(ColumnTarget.SERIES_INDEX)?.let(XlsxReader::number),
            tags = tags.distinctBy(String::lowercase),
            readings = readings,
            lentTo = lent,
            owned = first(ColumnTarget.GONE)?.let(ColumnTarget::parseBoolean) != true,
            locationPath = first(ColumnTarget.LOCATION),
            notes = notes.joinToString("\n").ifEmpty { null },
        )
    }

    /**
     * Eén kolom met volledige namen ("A; B", "A & B", "Achternaam, Voornaam"), of losse kolommen
     * voor voornaam, tussenvoegsel en achternaam. Een tussenvoegsel na een voornaam schrijf je klein.
     */
    internal fun authors(full: String?, first: String?, infix: String?, last: String?): List<String> {
        if (last != null) {
            val parts = listOfNotNull(first, infix?.let { if (first != null) it.lowercase() else it }, last)
            return listOf(parts.joinToString(" ").replace(Regex("\\s+"), " ").trim())
        }
        if (full == null) return emptyList()
        val names = full.split(';', '&', '/').map(String::trim).filter(String::isNotEmpty)
        if (names.size == 1) {
            val commaParts = full.split(',').map(String::trim)
            // "Tolkien, J.R.R." → "J.R.R. Tolkien"; "A, B, C" blijven drie auteurs.
            if (commaParts.size == 2 && commaParts.none { it.contains(' ') && it.split(' ').size > 2 } && !commaParts[1].contains(' ')) {
                return listOf("${commaParts[1]} ${commaParts[0]}")
            }
            return commaParts.filter(String::isNotEmpty)
        }
        return names
    }

    /** "nl", "dut", "Nederlands" of "Dutch" → "nl". */
    internal fun languageCode(text: String): String? {
        LanguageCodes.toIso1(text)?.takeIf { it in Locale.getISOLanguages() }?.let { return it }
        val name = TextNormalizer.normalize(text)
        return Locale.getISOLanguages().firstOrNull { code ->
            val locale = Locale.forLanguageTag(code)
            listOf(Locale.forLanguageTag("nl"), Locale.ENGLISH, locale).any { TextNormalizer.normalize(locale.getDisplayLanguage(it)) == name }
        }
    }

    /** Datum als tekst, of als Excel-datum (dagen sinds 30-12-1899). */
    internal fun date(text: String): LocalDate? {
        val trimmed = text.trim()
        trimmed.toDoubleOrNull()?.let { serial ->
            return if (serial in 20_000.0..80_000.0) LocalDate.of(1899, 12, 30).plusDays(serial.toLong()) else null
        }
        return dateFormats.firstNotNullOfOrNull { runCatching { LocalDate.parse(trimmed.take(10), it) }.getOrNull() }
    }
}
