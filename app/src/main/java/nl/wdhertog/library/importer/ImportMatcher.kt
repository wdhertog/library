package nl.wdhertog.library.importer

import nl.wdhertog.library.util.TextNormalizer

/** Een boek dat al in de bibliotheek staat, met wat nodig is om het te herkennen. */
data class ExistingBook(
    val id: Long,
    val title: String,
    val authors: List<String>,
    val isbn13: String?,
    val hirtId: Long?,
    val externalIds: Set<String> = emptySet(),
)

enum class MatchReason(val label: String) {
    HIRT_ID("eerder geïmporteerd"),
    EXTERNAL_ID("eerder geïmporteerd"),
    ISBN("zelfde ISBN"),
    TITLE_AUTHOR("zelfde titel en auteur"),
    /** Kan een andere editie zijn, of hetzelfde boek waarbij het bestand een ander ISBN koos. */
    OTHER_ISBN("zelfde titel, ander ISBN"),
}

data class Match(val existing: ExistingBook, val reason: MatchReason) {
    /** Bij twijfel beslis je zelf; standaard wordt het dan een apart boek. */
    val certain get() = reason != MatchReason.OTHER_ISBN
}

/**
 * Zoekt per geïmporteerd boek een bestaand boek: eerst op Have I Read That-id (opnieuw importeren),
 * dan op ISBN, en alleen als één van beide geen ISBN heeft op titel + eerste auteur
 * (twee verschillende ISBN's zijn meestal verschillende edities; zo'n boek komt terug als twijfelgeval).
 * Elk bestaand boek wordt hooguit één keer gekoppeld.
 */
object ImportMatcher {

    fun match(imported: List<ImportedBook>, existing: List<ExistingBook>): List<Match?> {
        val byHirt = existing.filter { it.hirtId != null }.associateBy { it.hirtId }
        val byExternal = existing.flatMap { book -> book.externalIds.map { it to book } }.toMap()
        val byIsbn = existing.filter { it.isbn13 != null }.groupBy { it.isbn13 }
        val byKey = existing.groupBy { key(it.title, it.authors) }
        val taken = mutableSetOf<Long>()

        fun claim(book: ExistingBook?, reason: MatchReason): Match? =
            book?.takeIf { taken.add(it.id) }?.let { Match(it, reason) }

        // Eerst alle zekere koppelingen, zodat een twijfelgeval nooit een zeker geval wegkaapt.
        val certain = imported.map { book ->
            book.hirtId?.let { claim(byHirt[it], MatchReason.HIRT_ID) }
                ?: book.externalIds.firstNotNullOfOrNull { byExternal[it] }?.let { claim(it, MatchReason.EXTERNAL_ID) }
                ?: book.isbn13?.let { isbn -> byIsbn[isbn]?.firstOrNull { it.id !in taken }?.let { claim(it, MatchReason.ISBN) } }
                ?: key(book.title, book.authors)?.let { byKey[it] }
                    ?.firstOrNull { it.id !in taken && (it.isbn13 == null || book.isbn13 == null) }
                    ?.let { claim(it, MatchReason.TITLE_AUTHOR) }
        }
        return imported.zip(certain) { book, match ->
            match ?: key(book.title, book.authors)?.let { byKey[it] }
                ?.firstOrNull { it.id !in taken }
                ?.let { claim(it, MatchReason.OTHER_ISBN) }
        }
    }

    /** Titel zonder ondertitel en lidwoord, plus de achternaam van de eerste auteur. */
    private fun key(title: String, authors: List<String>): String? {
        val mainTitle = TextNormalizer.titleSortKey(title.substringBefore(":"))
        val author = authors.firstOrNull()?.let { TextNormalizer.normalize(it).split(' ').last() }
        if (mainTitle.isBlank() || author.isNullOrBlank()) return null
        return "$mainTitle|$author"
    }
}
